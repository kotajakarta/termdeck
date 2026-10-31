package id.termdeck.app.server

import id.termdeck.app.data.StoreBackend
import id.termdeck.app.ssh.SshPoolBackend
import id.termdeck.app.ssh.shlexQuote
import id.termdeck.app.ssh.sshErrorMessage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

@Serializable
data class ContainerActionRequest(
    val server_id: Int,
    val container_id: String,
    val action: String,
    val force: Boolean = false
)

@Serializable
data class ImagePullRequest(
    val server_id: Int,
    val image: String
)

@Serializable
data class ContainerRunRequest(
    val server_id: Int,
    val image: String,
    val name: String? = null,
    val ports: List<String> = emptyList(),
    val env: List<String> = emptyList(),
    val volumes: List<String> = emptyList(),
    val command: String? = null,
    val restart: String? = null
)

internal val JsonElement?.primitiveOrNull: JsonPrimitive?
    get() = if (this != null && this !is JsonNull && this is JsonPrimitive) this else null

private suspend fun runRemote(pool: SshPoolBackend, serverId: Int, command: String, timeoutMs: Long = 30_000L): Pair<Int, String> {
    return try {
        val res = pool.execCommand(serverId, command, stdinText = "", timeoutMs = timeoutMs)
        res.exitStatus to (res.stdout.ifEmpty { res.stderr })
    } catch (t: Throwable) {
        throw HttpError(HttpStatusCode.BadGateway, "SSH error: ${sshErrorMessage(t)}")
    }
}

private suspend fun detectRemoteEngine(pool: SshPoolBackend, serverId: Int): JsonObject {
    // 1. Check podman
    val (podmanCode, podmanOut) = runRemote(pool, serverId, "podman --version 2>/dev/null", 10_000L)
    if (podmanCode == 0 && podmanOut.contains("podman", ignoreCase = true)) {
        val ver = Regex("podman\\s+version\\s+([^\\s,]+)", RegexOption.IGNORE_CASE).find(podmanOut)?.groupValues?.get(1)
            ?: podmanOut.trim()
        val (_, rootlessOut) = runRemote(pool, serverId, "podman info --format '{{.Host.Security.Rootless}}' 2>/dev/null", 10_000L)
        val isRootless = rootlessOut.contains("true", ignoreCase = true)
        return buildJsonObject {
            put("engine", JsonPrimitive("podman"))
            put("version", JsonPrimitive(ver))
            put("rootless", JsonPrimitive(isRootless))
            put("error", JsonNull)
        }
    }

    // 2. Check docker
    val (dockerCode, dockerOut) = runRemote(pool, serverId, "docker --version 2>/dev/null", 10_000L)
    if (dockerCode == 0 && dockerOut.contains("docker", ignoreCase = true)) {
        val ver = Regex("Docker\\s+version\\s+([^\\s,]+)", RegexOption.IGNORE_CASE).find(dockerOut)?.groupValues?.get(1)
            ?: dockerOut.trim()
        val (_, secOut) = runRemote(pool, serverId, "docker info --format '{{.SecurityOptions}}' 2>/dev/null", 10_000L)
        val isRootless = secOut.contains("rootless", ignoreCase = true)
        return buildJsonObject {
            put("engine", JsonPrimitive("docker"))
            put("version", JsonPrimitive(ver))
            put("rootless", JsonPrimitive(isRootless))
            put("error", JsonNull)
        }
    }

    return buildJsonObject {
        put("engine", JsonPrimitive("none"))
        put("version", JsonPrimitive(""))
        put("rootless", JsonPrimitive(false))
        put("error", JsonPrimitive("Podman atau Docker tidak terdeteksi pada server remote ini."))
    }
}

internal fun parsePortsJson(portsElement: JsonElement?): JsonArray {
    return buildJsonArray {
        if (portsElement == null || portsElement is JsonNull) return@buildJsonArray

        fun parseStringPort(raw: String) {
            for (part in raw.split(',')) {
                val trimmed = part.trim()
                if (trimmed.isEmpty() || trimmed == "null") continue
                if (trimmed.contains("->")) {
                    val split = trimmed.split("->", limit = 2)
                    val hostPart = split[0]
                    val contPart = split[1]
                    val proto = if (contPart.contains('/')) contPart.substringAfter('/') else "tcp"
                    val cPort = contPart.substringBefore('/').toIntOrNull() ?: 0
                    val hPort = if (hostPart.contains(':')) hostPart.substringAfterLast(':').toIntOrNull() ?: 0 else hostPart.toIntOrNull() ?: 0
                    val hIp = if (hostPart.contains(':')) hostPart.substringBeforeLast(':') else ""
                    add(buildJsonObject {
                        put("host_ip", JsonPrimitive(hIp))
                        put("host_port", JsonPrimitive(hPort))
                        put("container_port", JsonPrimitive(cPort))
                        put("protocol", JsonPrimitive(proto))
                    })
                } else if (trimmed.contains('/')) {
                    val cPort = trimmed.substringBefore('/').toIntOrNull() ?: 0
                    val proto = trimmed.substringAfter('/')
                    add(buildJsonObject {
                        put("host_ip", JsonPrimitive(""))
                        put("host_port", JsonPrimitive(0))
                        put("container_port", JsonPrimitive(cPort))
                        put("protocol", JsonPrimitive(proto))
                    })
                }
            }
        }

        fun parseSinglePortObject(obj: JsonObject) {
            val hIp = obj["hostIP"]?.primitiveOrNull?.content
                ?: obj["host_ip"]?.primitiveOrNull?.content
                ?: obj["HostIp"]?.primitiveOrNull?.content
                ?: ""
            val hPort = obj["hostPort"]?.primitiveOrNull?.intOrNull
                ?: obj["host_port"]?.primitiveOrNull?.intOrNull
                ?: obj["HostPort"]?.primitiveOrNull?.intOrNull
                ?: 0
            val cPort = obj["containerPort"]?.primitiveOrNull?.intOrNull
                ?: obj["container_port"]?.primitiveOrNull?.intOrNull
                ?: obj["ContainerPort"]?.primitiveOrNull?.intOrNull
                ?: 0
            val proto = obj["protocol"]?.primitiveOrNull?.content
                ?: obj["Protocol"]?.primitiveOrNull?.content
                ?: "tcp"

            if (cPort > 0 || hPort > 0) {
                add(buildJsonObject {
                    put("host_ip", JsonPrimitive(hIp))
                    put("host_port", JsonPrimitive(hPort))
                    put("container_port", JsonPrimitive(cPort))
                    put("protocol", JsonPrimitive(proto))
                })
            }
        }

        when (portsElement) {
            is JsonArray -> {
                for (item in portsElement) {
                    when (item) {
                        is JsonObject -> parseSinglePortObject(item)
                        is JsonPrimitive -> if (item !is JsonNull) parseStringPort(item.content)
                        else -> Unit
                    }
                }
            }
            is JsonObject -> {
                if (portsElement.containsKey("container_port") || portsElement.containsKey("containerPort") || portsElement.containsKey("host_port")) {
                    parseSinglePortObject(portsElement)
                } else {
                    for ((key, value) in portsElement) {
                        val proto = if (key.contains('/')) key.substringAfter('/') else "tcp"
                        val cPort = key.substringBefore('/').toIntOrNull() ?: 0
                        if (value is JsonArray) {
                            for (sub in value) {
                                if (sub is JsonObject) {
                                    val hIp = sub["HostIp"]?.primitiveOrNull?.content ?: sub["host_ip"]?.primitiveOrNull?.content ?: ""
                                    val hPort = sub["HostPort"]?.primitiveOrNull?.intOrNull ?: sub["host_port"]?.primitiveOrNull?.intOrNull ?: 0
                                    add(buildJsonObject {
                                        put("host_ip", JsonPrimitive(hIp))
                                        put("host_port", JsonPrimitive(hPort))
                                        put("container_port", JsonPrimitive(cPort))
                                        put("protocol", JsonPrimitive(proto))
                                    })
                                } else if (sub is JsonPrimitive) {
                                    val subProto = sub.primitiveOrNull?.content ?: proto
                                    add(buildJsonObject {
                                        put("host_ip", JsonPrimitive(""))
                                        put("host_port", JsonPrimitive(0))
                                        put("container_port", JsonPrimitive(cPort))
                                        put("protocol", JsonPrimitive(subProto))
                                    })
                                }
                            }
                        } else if (cPort > 0) {
                            add(buildJsonObject {
                                put("host_ip", JsonPrimitive(""))
                                put("host_port", JsonPrimitive(0))
                                put("container_port", JsonPrimitive(cPort))
                                put("protocol", JsonPrimitive(proto))
                            })
                        }
                    }
                }
            }
            is JsonPrimitive -> {
                if (portsElement !is JsonNull) {
                    parseStringPort(portsElement.content)
                }
            }
        }
    }
}

internal fun normalizeContainerJson(raw: JsonObject): JsonObject {
    val cidFull = raw["Id"]?.primitiveOrNull?.content
        ?: raw["ID"]?.primitiveOrNull?.content
        ?: raw["id"]?.primitiveOrNull?.content
        ?: ""
    val cid = if (cidFull.length > 12 && !cidFull.contains('-')) cidFull.take(12) else cidFull

    val nameList = mutableListOf<String>()
    val namesElem = raw["Names"] ?: raw["names"] ?: raw["Name"] ?: raw["name"]
    when (namesElem) {
        is JsonArray -> {
            for (n in namesElem) {
                val str = n.primitiveOrNull?.content?.trim()?.removePrefix("/") ?: ""
                if (str.isNotEmpty()) nameList.add(str)
            }
        }
        is JsonPrimitive -> {
            val strVal = namesElem.primitiveOrNull?.content
            if (!strVal.isNullOrBlank()) {
                for (str in strVal.split(',')) {
                    val clean = str.trim().removePrefix("/")
                    if (clean.isNotEmpty()) nameList.add(clean)
                }
            }
        }
        else -> Unit
    }
    if (nameList.isEmpty()) nameList.add(cid)

    val names = buildJsonArray {
        for (n in nameList) add(JsonPrimitive(n))
    }

    val image = raw["Image"]?.primitiveOrNull?.content
        ?: raw["ImageName"]?.primitiveOrNull?.content
        ?: raw["image"]?.primitiveOrNull?.content
        ?: ""

    val rawStateElem = raw["State"] ?: raw["state"]
    val rawState = when (rawStateElem) {
        is JsonObject -> rawStateElem["Status"]?.primitiveOrNull?.content?.lowercase() ?: ""
        else -> rawStateElem?.primitiveOrNull?.content?.lowercase() ?: ""
    }
    val rawStatus = raw["Status"]?.primitiveOrNull?.content ?: raw["status"]?.primitiveOrNull?.content ?: ""

    val state = when {
        rawStatus.contains("up", ignoreCase = true) || rawState.contains("running") -> "running"
        rawState.contains("paused") || rawStatus.contains("paused", ignoreCase = true) -> "paused"
        rawState.contains("exited") || rawStatus.contains("exited", ignoreCase = true) || rawState.contains("stopped") -> "exited"
        rawState.contains("created") -> "created"
        rawState.contains("restarting") -> "restarting"
        rawState.contains("dead") -> "dead"
        else -> if (rawState.isNotEmpty()) rawState else "unknown"
    }

    val rawPortsElem = raw["Ports"] ?: raw["ports"]
    val portsCandidate = if (rawPortsElem != null && rawPortsElem !is JsonNull) rawPortsElem else raw["ExposedPorts"]
    var ports = parsePortsJson(portsCandidate)
    if (ports.isEmpty() && raw["ExposedPorts"] != null && raw["ExposedPorts"] !is JsonNull) {
        val exposed = parsePortsJson(raw["ExposedPorts"])
        if (exposed.isNotEmpty()) {
            ports = exposed
        }
    }
    val created = raw["CreatedAt"]?.primitiveOrNull?.content
        ?: raw["Created"]?.primitiveOrNull?.content
        ?: raw["created"]?.primitiveOrNull?.content
        ?: ""

    val cmdElem = raw["Command"] ?: raw["command"]
    val command = when {
        cmdElem is JsonArray -> cmdElem.mapNotNull { it.primitiveOrNull?.content }.joinToString(" ")
        cmdElem != null -> cmdElem.primitiveOrNull?.content ?: ""
        else -> ""
    }

    return buildJsonObject {
        put("id", JsonPrimitive(cid))
        put("names", names)
        put("image", JsonPrimitive(image))
        put("state", JsonPrimitive(state))
        put("status", JsonPrimitive(rawStatus.ifEmpty { state.replaceFirstChar { it.uppercase() } }))
        put("created", JsonPrimitive(created))
        put("ports", ports)
        put("command", JsonPrimitive(command))
    }
}

fun Route.registerContainersRoutes(
    handleApiFn: suspend (ApplicationCall, suspend () -> Unit) -> Unit,
    requireUnlockedFn: (ApplicationCall) -> Pair<StoreBackend, SshPoolBackend>,
    ensureServerFn: (StoreBackend, Int) -> Any
) {
    route("/containers") {
        get("/engine") {
            handleApiFn(call) {
                val (store, pool) = requireUnlockedFn(call)
                val serverId = call.request.queryParameters["server_id"]?.toIntOrNull()
                    ?: throw HttpError(HttpStatusCode.BadRequest, "Missing server_id")
                ensureServerFn(store, serverId)
                val status = detectRemoteEngine(pool, serverId)
                call.respondText(AppJson.encodeToString(status), ContentType.Application.Json)
            }
        }

        get("/list") {
            handleApiFn(call) {
                val (store, pool) = requireUnlockedFn(call)
                val serverId = call.request.queryParameters["server_id"]?.toIntOrNull()
                    ?: throw HttpError(HttpStatusCode.BadRequest, "Missing server_id")
                ensureServerFn(store, serverId)
                val engineObj = detectRemoteEngine(pool, serverId)
                val engine = engineObj["engine"]?.primitiveOrNull?.content ?: "none"
                if (engine == "none") {
                    call.respondText(AppJson.encodeToString(buildJsonArray { }), ContentType.Application.Json)
                    return@handleApiFn
                }

                val all = call.request.queryParameters["all"] != "false"
                val allFlag = if (all) "-a " else ""

                if (engine == "podman") {
                    val (code, out) = runRemote(pool, serverId, "podman ps ${allFlag}--format json", 15_000L)
                    if (code != 0) throw HttpError(HttpStatusCode.InternalServerError, "Podman error: $out")
                    val items = buildJsonArray {
                        val parsed = runCatching { AppJson.parseToJsonElement(out.trim()) }.getOrNull()
                        if (parsed is JsonArray) {
                            for (item in parsed) {
                                if (item is JsonObject) add(normalizeContainerJson(item))
                            }
                        } else if (parsed is JsonObject) {
                            add(normalizeContainerJson(parsed))
                        }
                    }
                    call.respondText(AppJson.encodeToString(items), ContentType.Application.Json)
                } else {
                    val (code, out) = runRemote(pool, serverId, "docker ps ${allFlag}--format '{{json .}}'", 15_000L)
                    if (code != 0) throw HttpError(HttpStatusCode.InternalServerError, "Docker error: $out")
                    val items = buildJsonArray {
                        for (line in out.trim().lines()) {
                            val clean = line.trim()
                            if (clean.isEmpty()) continue
                            val parsed = runCatching { AppJson.parseToJsonElement(clean).jsonObject }.getOrNull()
                            if (parsed != null) add(normalizeContainerJson(parsed))
                        }
                    }
                    call.respondText(AppJson.encodeToString(items), ContentType.Application.Json)
                }
            }
        }

        post("/action") {
            handleApiFn(call) {
                val (store, pool) = requireUnlockedFn(call)
                val body = call.receive<ContainerActionRequest>()
                ensureServerFn(store, body.server_id)
                val engineObj = detectRemoteEngine(pool, body.server_id)
                val engine = engineObj["engine"]?.primitiveOrNull?.content ?: "none"
                if (engine == "none") {
                    throw HttpError(HttpStatusCode.BadRequest, "No container engine found")
                }

                val cidQ = shlexQuote(body.container_id)
                val cmd = if (body.action == "delete") {
                    val flag = if (body.force) "-f " else ""
                    "$engine rm $flag$cidQ"
                } else {
                    "$engine ${body.action} $cidQ"
                }

                val (code, out) = runRemote(pool, body.server_id, cmd, 20_000L)
                if (code != 0) {
                    throw HttpError(HttpStatusCode.BadRequest, out.trim().ifEmpty { "Aksi ${body.action} gagal" })
                }
                val resp = buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("message", JsonPrimitive("Kontainer ${body.container_id} berhasil di-${body.action}"))
                }
                call.respondText(AppJson.encodeToString(resp), ContentType.Application.Json)
            }
        }

        get("/logs") {
            handleApiFn(call) {
                val (store, pool) = requireUnlockedFn(call)
                val serverId = call.request.queryParameters["server_id"]?.toIntOrNull()
                    ?: throw HttpError(HttpStatusCode.BadRequest, "Missing server_id")
                val containerId = call.request.queryParameters["container_id"]
                    ?: throw HttpError(HttpStatusCode.BadRequest, "Missing container_id")
                val tail = call.request.queryParameters["tail"]?.toIntOrNull() ?: 150
                ensureServerFn(store, serverId)

                val engineObj = detectRemoteEngine(pool, serverId)
                val engine = engineObj["engine"]?.primitiveOrNull?.content ?: "none"
                if (engine == "none") throw HttpError(HttpStatusCode.BadRequest, "No container engine found")

                val (_, out) = runRemote(pool, serverId, "$engine logs --tail $tail ${shlexQuote(containerId)} 2>&1", 15_000L)
                val resp = buildJsonObject {
                    put("logs", JsonPrimitive(out))
                }
                call.respondText(AppJson.encodeToString(resp), ContentType.Application.Json)
            }
        }

        get("/images") {
            handleApiFn(call) {
                val (store, pool) = requireUnlockedFn(call)
                val serverId = call.request.queryParameters["server_id"]?.toIntOrNull()
                    ?: throw HttpError(HttpStatusCode.BadRequest, "Missing server_id")
                ensureServerFn(store, serverId)

                val engineObj = detectRemoteEngine(pool, serverId)
                val engine = engineObj["engine"]?.primitiveOrNull?.content ?: "none"
                if (engine == "none") {
                    call.respondText(AppJson.encodeToString(buildJsonArray { }), ContentType.Application.Json)
                    return@handleApiFn
                }

                if (engine == "podman") {
                    val (code, out) = runRemote(pool, serverId, "podman images --format json", 15_000L)
                    if (code != 0) throw HttpError(HttpStatusCode.InternalServerError, "Podman images error: $out")
                    val items = buildJsonArray {
                        val parsed = runCatching { AppJson.parseToJsonElement(out.trim()) }.getOrNull()
                        val list = if (parsed is JsonArray) parsed else if (parsed is JsonObject) JsonArray(listOf(parsed)) else JsonArray(emptyList())
                        for (elem in list) {
                            if (elem !is JsonObject) continue
                            val iid = (elem["Id"]?.primitiveOrNull?.content ?: elem["id"]?.primitiveOrNull?.content ?: "").take(12)
                            var repo = elem["Repository"]?.primitiveOrNull?.content ?: ""
                            var tag = elem["Tag"]?.primitiveOrNull?.content ?: ""
                            if (repo.isEmpty()) {
                                val tagList = when {
                                    elem["Names"] is JsonArray -> elem["Names"] as JsonArray
                                    elem["RepoTags"] is JsonArray -> elem["RepoTags"] as JsonArray
                                    elem["History"] is JsonArray -> elem["History"] as JsonArray
                                    else -> null
                                }
                                val first = tagList?.firstOrNull()?.primitiveOrNull?.content
                                    ?: elem["Names"]?.primitiveOrNull?.content
                                    ?: ""
                                if (first.isNotEmpty()) {
                                    if (first.contains(':')) {
                                        repo = first.substringBeforeLast(':')
                                        tag = first.substringAfterLast(':')
                                    } else {
                                        repo = first
                                    }
                                }
                            }
                            val sizeVal = elem["Size"]?.primitiveOrNull?.content?.toLongOrNull()
                                ?: elem["VirtualSize"]?.primitiveOrNull?.content?.toLongOrNull()
                            val sizeStr = when {
                                sizeVal != null && sizeVal >= 1024L * 1024L * 1024L ->
                                    String.format("%.2f GB", sizeVal / (1024.0 * 1024.0 * 1024.0))
                                sizeVal != null && sizeVal > 0 ->
                                    String.format("%.1f MB", sizeVal / (1024.0 * 1024.0))
                                else -> elem["Size"]?.primitiveOrNull?.content ?: ""
                            }
                            add(buildJsonObject {
                                put("id", JsonPrimitive(iid))
                                put("repository", JsonPrimitive(repo.ifEmpty { "<none>" }))
                                put("tag", JsonPrimitive(tag.ifEmpty { "<none>" }))
                                put("size", JsonPrimitive(sizeStr))
                                put("created", JsonPrimitive(elem["Created"]?.primitiveOrNull?.content ?: elem["CreatedAt"]?.primitiveOrNull?.content ?: ""))
                            })
                        }
                    }
                    call.respondText(AppJson.encodeToString(items), ContentType.Application.Json)
                } else {
                    val (code, out) = runRemote(pool, serverId, "docker images --format '{{json .}}'", 15_000L)
                    if (code != 0) throw HttpError(HttpStatusCode.InternalServerError, "Docker images error: $out")
                    val items = buildJsonArray {
                        for (line in out.trim().lines()) {
                            val clean = line.trim()
                            if (clean.isEmpty()) continue
                            val obj = runCatching { AppJson.parseToJsonElement(clean).jsonObject }.getOrNull() ?: continue
                            var repo = obj["Repository"]?.primitiveOrNull?.content ?: ""
                            var tag = obj["Tag"]?.primitiveOrNull?.content ?: ""
                            if (repo.isEmpty() || repo == "<none>") {
                                val repoTags = obj["RepoTags"]
                                if (repoTags is JsonArray) {
                                    val first = repoTags.firstOrNull()?.primitiveOrNull?.content ?: ""
                                    if (first.contains(':')) {
                                        repo = first.substringBeforeLast(':')
                                        tag = first.substringAfterLast(':')
                                    } else if (first.isNotEmpty()) {
                                        repo = first
                                    }
                                }
                            }
                            add(buildJsonObject {
                                put("id", JsonPrimitive((obj["ID"]?.primitiveOrNull?.content ?: "").take(12)))
                                put("repository", JsonPrimitive(repo.ifEmpty { "<none>" }))
                                put("tag", JsonPrimitive(tag.ifEmpty { "<none>" }))
                                put("size", JsonPrimitive(obj["Size"]?.primitiveOrNull?.content ?: ""))
                                put("created", JsonPrimitive(obj["CreatedAt"]?.primitiveOrNull?.content ?: obj["CreatedSince"]?.primitiveOrNull?.content ?: ""))
                            })
                        }
                    }
                    call.respondText(AppJson.encodeToString(items), ContentType.Application.Json)
                }
            }
        }

        post("/images/pull") {
            handleApiFn(call) {
                val (store, pool) = requireUnlockedFn(call)
                val body = call.receive<ImagePullRequest>()
                ensureServerFn(store, body.server_id)

                val engineObj = detectRemoteEngine(pool, body.server_id)
                val engine = engineObj["engine"]?.primitiveOrNull?.content ?: "none"
                if (engine == "none") throw HttpError(HttpStatusCode.BadRequest, "No container engine found")

                val (code, out) = runRemote(pool, body.server_id, "$engine pull ${shlexQuote(body.image.trim())}", 180_000L)
                if (code != 0) throw HttpError(HttpStatusCode.BadRequest, out.trim().ifEmpty { "Gagal menarik image" })

                val resp = buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("message", JsonPrimitive("Image ${body.image} berhasil diunduh"))
                }
                call.respondText(AppJson.encodeToString(resp), ContentType.Application.Json)
            }
        }

        delete("/images") {
            handleApiFn(call) {
                val (store, pool) = requireUnlockedFn(call)
                val serverId = call.request.queryParameters["server_id"]?.toIntOrNull()
                    ?: throw HttpError(HttpStatusCode.BadRequest, "Missing server_id")
                val imageId = call.request.queryParameters["image_id"]
                    ?: throw HttpError(HttpStatusCode.BadRequest, "Missing image_id")
                val force = call.request.queryParameters["force"] == "true"
                ensureServerFn(store, serverId)

                val engineObj = detectRemoteEngine(pool, serverId)
                val engine = engineObj["engine"]?.primitiveOrNull?.content ?: "none"
                if (engine == "none") throw HttpError(HttpStatusCode.BadRequest, "No container engine found")

                val flag = if (force) "-f " else ""
                val (code, out) = runRemote(pool, serverId, "$engine rmi $flag${shlexQuote(imageId)}", 20_000L)
                if (code != 0) throw HttpError(HttpStatusCode.BadRequest, out.trim().ifEmpty { "Gagal menghapus image" })

                val resp = buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("message", JsonPrimitive("Image $imageId berhasil dihapus"))
                }
                call.respondText(AppJson.encodeToString(resp), ContentType.Application.Json)
            }
        }

        post("/run") {
            handleApiFn(call) {
                val (store, pool) = requireUnlockedFn(call)
                val body = call.receive<ContainerRunRequest>()
                ensureServerFn(store, body.server_id)

                val engineObj = detectRemoteEngine(pool, body.server_id)
                val engine = engineObj["engine"]?.primitiveOrNull?.content ?: "none"
                if (engine == "none") throw HttpError(HttpStatusCode.BadRequest, "No container engine found")

                val parts = mutableListOf(engine, "run", "-d")
                if (!body.name.isNullOrBlank()) {
                    parts.add("--name")
                    parts.add(shlexQuote(body.name.trim()))
                }
                for (p in body.ports) {
                    if (p.isNotBlank()) {
                        parts.add("-p")
                        parts.add(shlexQuote(p.trim()))
                    }
                }
                for (e in body.env) {
                    if (e.isNotBlank()) {
                        parts.add("-e")
                        parts.add(shlexQuote(e.trim()))
                    }
                }
                for (v in body.volumes) {
                    if (v.isNotBlank()) {
                        parts.add("-v")
                        parts.add(shlexQuote(v.trim()))
                    }
                }
                if (!body.restart.isNullOrBlank()) {
                    parts.add("--restart")
                    parts.add(shlexQuote(body.restart.trim()))
                }
                parts.add(shlexQuote(body.image.trim()))
                if (!body.command.isNullOrBlank()) {
                    parts.add(body.command.trim())
                }

                val (code, out) = runRemote(pool, body.server_id, parts.joinToString(" "), 30_000L)
                if (code != 0) throw HttpError(HttpStatusCode.BadRequest, out.trim().ifEmpty { "Gagal menjalankan kontainer baru" })

                val newCid = out.trim().take(12)
                val resp = buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("id", JsonPrimitive(newCid))
                    put("message", JsonPrimitive("Kontainer $newCid berhasil dijalankan"))
                }
                call.respondText(AppJson.encodeToString(resp), ContentType.Application.Json)
            }
        }
    }
}

