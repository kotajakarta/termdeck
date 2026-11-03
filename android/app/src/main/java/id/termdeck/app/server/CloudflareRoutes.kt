package id.termdeck.app.server

import id.termdeck.app.data.CFAccountIn
import id.termdeck.app.data.CFAccountUpdateIn
import id.termdeck.app.data.CFAccountVerifyIn
import id.termdeck.app.data.CFRouteIn
import id.termdeck.app.data.StoreBackend
import id.termdeck.app.data.validateCfAccountIn
import id.termdeck.app.ssh.SshPoolBackend
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
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

suspend fun defaultCfRequest(
    method: String,
    path: String,
    token: String,
    body: String? = null
): JsonElement = withContext(Dispatchers.IO) {
    val url = java.net.URL("https://api.cloudflare.com/client/v4$path")
    val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
        requestMethod = method
        setRequestProperty("Authorization", "Bearer $token")
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("User-Agent", "TermDeck-Cloudflare/1.0")
        connectTimeout = 15_000
        readTimeout = 15_000
        if (body != null) {
            doOutput = true
            outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
    }
    try {
        val code = conn.responseCode
        if (code in 200..299) {
            val respText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            AppJson.parseToJsonElement(respText)
        } else {
            var errMsg = "Cloudflare API error ($code)"
            val errText = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            if (!errText.isNullOrBlank()) {
                runCatching {
                    val errJson = AppJson.parseToJsonElement(errText).jsonObject
                    val errors = errJson["errors"] as? JsonArray
                    if (errors != null && errors.isNotEmpty()) {
                        val firstErr = (errors[0] as? JsonObject)?.get("message")?.jsonPrimitive?.content
                        if (!firstErr.isNullOrBlank()) errMsg = firstErr
                    }
                }
            }
            val status = if (code < 500) HttpStatusCode.fromValue(code) else HttpStatusCode.BadGateway
            throw HttpError(status, errMsg)
        }
    } catch (e: HttpError) {
        throw e
    } catch (e: Exception) {
        throw HttpError(HttpStatusCode.GatewayTimeout, "Failed to connect to Cloudflare: ${e.message}")
    } finally {
        conn.disconnect()
    }
}

fun Route.registerCloudflareRoutes(
    handleApiFn: suspend (ApplicationCall, suspend () -> Unit) -> Unit,
    requireUnlockedFn: (ApplicationCall) -> Pair<StoreBackend, SshPoolBackend>,
    cfHttpClient: (suspend (method: String, path: String, token: String, body: String?) -> JsonElement)? = null
) {
    val cfRequest: suspend (String, String, String, String?) -> JsonElement =
        cfHttpClient ?: { method, path, token, body -> defaultCfRequest(method, path, token, body) }

    route("/cf") {
        post("/accounts/verify") {
            handleApiFn(call) {
                requireUnlockedFn(call)
                val payload = call.receive<CFAccountVerifyIn>()
                val token = payload.token.trim()
                val res = cfRequest("GET", "/accounts", token, null).jsonObject
                val accounts = res["result"] as? JsonArray ?: JsonArray(emptyList())
                if (accounts.isEmpty()) {
                    throw HttpError(HttpStatusCode.BadRequest, "Token tidak memiliki akses ke akun Cloudflare mana pun.")
                }
                val firstAcc = (accounts[0] as? JsonObject) ?: throw HttpError(HttpStatusCode.BadRequest, "Data akun Cloudflare tidak valid.")
                val accountId = firstAcc["id"]?.jsonPrimitive?.content ?: ""
                val accountName = firstAcc["name"]?.jsonPrimitive?.content ?: "Unnamed Account"

                var hasDnsAccess = false
                try {
                    val zRes = cfRequest("GET", "/zones?status=active&account.id=$accountId&per_page=1", token, null).jsonObject
                    val zones = zRes["result"] as? JsonArray ?: JsonArray(emptyList())
                    if (zones.isNotEmpty()) {
                        val zid = (zones[0] as? JsonObject)?.get("id")?.jsonPrimitive?.content ?: ""
                        cfRequest("GET", "/zones/$zid/dns_records?per_page=1", token, null)
                        hasDnsAccess = true
                    }
                } catch (_: Exception) {
                    hasDnsAccess = false
                }

                call.respondText(
                    AppJson.encodeToString(
                        buildJsonObject {
                            put("account_id", accountId)
                            put("account_name", accountName)
                            put("has_dns_access", hasDnsAccess)
                        }
                    ),
                    ContentType.Application.Json
                )
            }
        }

        get("/accounts") {
            handleApiFn(call) {
                val (store, _) = requireUnlockedFn(call)
                val list = store.listCfAccounts()
                call.respond(list)
            }
        }

        post("/accounts") {
            handleApiFn(call) {
                val (store, _) = requireUnlockedFn(call)
                val payload = call.receive<CFAccountIn>()
                val name = payload.name.trim()
                val token = payload.token.trim()
                validateCfAccountIn(name, token)

                val res = cfRequest("GET", "/accounts", token, null).jsonObject
                val accounts = res["result"] as? JsonArray ?: JsonArray(emptyList())
                if (accounts.isEmpty()) {
                    throw HttpError(HttpStatusCode.BadRequest, "Token tidak valid atau tidak memiliki akses akun.")
                }
                val firstAcc = (accounts[0] as? JsonObject) ?: throw HttpError(HttpStatusCode.BadRequest, "Data akun Cloudflare tidak valid.")
                val accountId = firstAcc["id"]?.jsonPrimitive?.content ?: ""
                val accountName = firstAcc["name"]?.jsonPrimitive?.content ?: ""

                val created = store.createCfAccount(name, token, accountId, accountName)
                call.respond(created)
            }
        }

        put("/accounts/{accountId}") {
            handleApiFn(call) {
                val (store, _) = requireUnlockedFn(call)
                val accountId = call.parameters["accountId"]?.toIntOrNull()
                    ?: throw HttpError(HttpStatusCode.BadRequest, "ID akun tidak valid.")
                val payload = call.receive<CFAccountUpdateIn>()
                if (store.getCfAccount(accountId) == null) {
                    throw HttpError(HttpStatusCode.NotFound, "Akun Cloudflare tidak ditemukan.")
                }

                val name = payload.name.trim()
                val newToken = payload.token?.trim()?.ifEmpty { null }
                var cfAccId: String? = null
                var cfAccName: String? = null

                if (newToken != null) {
                    val res = cfRequest("GET", "/accounts", newToken, null).jsonObject
                    val accounts = res["result"] as? JsonArray ?: JsonArray(emptyList())
                    if (accounts.isEmpty()) {
                        throw HttpError(HttpStatusCode.BadRequest, "Token tidak valid atau tidak memiliki akses akun.")
                    }
                    val firstAcc = (accounts[0] as? JsonObject) ?: throw HttpError(HttpStatusCode.BadRequest, "Data akun Cloudflare tidak valid.")
                    cfAccId = firstAcc["id"]?.jsonPrimitive?.content
                    cfAccName = firstAcc["name"]?.jsonPrimitive?.content
                }

                val updated = store.updateCfAccount(accountId, name, newToken, cfAccId, cfAccName)
                    ?: throw HttpError(HttpStatusCode.NotFound, "Akun Cloudflare tidak ditemukan.")
                call.respond(updated)
            }
        }

        delete("/accounts/{accountId}") {
            handleApiFn(call) {
                val (store, _) = requireUnlockedFn(call)
                val accountId = call.parameters["accountId"]?.toIntOrNull()
                    ?: throw HttpError(HttpStatusCode.BadRequest, "ID akun tidak valid.")
                val ok = store.deleteCfAccount(accountId)
                if (!ok) {
                    throw HttpError(HttpStatusCode.NotFound, "Akun Cloudflare tidak ditemukan.")
                }
                call.respondText(AppJson.encodeToString(buildJsonObject { put("ok", true) }), ContentType.Application.Json)
            }
        }

        get("/accounts/{accountId}/tunnels") {
            handleApiFn(call) {
                val (store, _) = requireUnlockedFn(call)
                val accountId = call.parameters["accountId"]?.toIntOrNull()
                    ?: throw HttpError(HttpStatusCode.BadRequest, "ID akun tidak valid.")
                val acc = store.getCfAccount(accountId)
                    ?: throw HttpError(HttpStatusCode.NotFound, "Akun Cloudflare tidak ditemukan.")
                val res = cfRequest("GET", "/accounts/${acc.accountId}/cfd_tunnel?is_deleted=false", acc.token, null).jsonObject
                val tunnels = res["result"] as? JsonArray ?: JsonArray(emptyList())
                val mapped = buildJsonArray {
                    for (t in tunnels) {
                        val tObj = t as? JsonObject ?: continue
                        add(buildJsonObject {
                            put("id", tObj["id"]?.jsonPrimitive?.content ?: "")
                            put("name", tObj["name"]?.jsonPrimitive?.content ?: "")
                            put("status", tObj["status"]?.jsonPrimitive?.content ?: "inactive")
                            val conns = tObj["connections"] as? JsonArray
                            put("conns_count", conns?.size ?: 0)
                            put("created_at", tObj["created_at"]?.jsonPrimitive?.content ?: "")
                            val isRemote = tObj["remote_config"]?.jsonPrimitive?.booleanOrNull ?: false
                            put("remote_config", isRemote)
                        })
                    }
                }
                call.respondText(AppJson.encodeToString(mapped), ContentType.Application.Json)
            }
        }

        get("/accounts/{accountId}/zones") {
            handleApiFn(call) {
                val (store, _) = requireUnlockedFn(call)
                val accountId = call.parameters["accountId"]?.toIntOrNull()
                    ?: throw HttpError(HttpStatusCode.BadRequest, "ID akun tidak valid.")
                val acc = store.getCfAccount(accountId)
                    ?: throw HttpError(HttpStatusCode.NotFound, "Akun Cloudflare tidak ditemukan.")
                val res = cfRequest("GET", "/zones?status=active&account.id=${acc.accountId}", acc.token, null).jsonObject
                val zones = res["result"] as? JsonArray ?: JsonArray(emptyList())
                val mapped = buildJsonArray {
                    for (z in zones) {
                        val zObj = z as? JsonObject ?: continue
                        add(buildJsonObject {
                            put("id", zObj["id"]?.jsonPrimitive?.content ?: "")
                            put("name", zObj["name"]?.jsonPrimitive?.content ?: "")
                            put("status", zObj["status"]?.jsonPrimitive?.content ?: "active")
                        })
                    }
                }
                call.respondText(AppJson.encodeToString(mapped), ContentType.Application.Json)
            }
        }

        get("/accounts/{accountId}/tunnels/{tunnelId}/routes") {
            handleApiFn(call) {
                val (store, _) = requireUnlockedFn(call)
                val accountId = call.parameters["accountId"]?.toIntOrNull()
                    ?: throw HttpError(HttpStatusCode.BadRequest, "ID akun tidak valid.")
                val tunnelId = call.parameters["tunnelId"]
                    ?: throw HttpError(HttpStatusCode.BadRequest, "Missing tunnelId")
                val acc = store.getCfAccount(accountId)
                    ?: throw HttpError(HttpStatusCode.NotFound, "Akun Cloudflare tidak ditemukan.")
                val res = cfRequest("GET", "/accounts/${acc.accountId}/cfd_tunnel/$tunnelId/configurations", acc.token, null).jsonObject
                val config = (res["result"] as? JsonObject)?.get("config") as? JsonObject
                val ingress = config?.get("ingress") as? JsonArray ?: JsonArray(emptyList())
                val mapped = buildJsonArray {
                    for (rule in ingress) {
                        val rObj = rule as? JsonObject ?: continue
                        val host = rObj["hostname"]?.jsonPrimitive?.content
                        val svc = rObj["service"]?.jsonPrimitive?.content
                        if (host != null && svc != null) {
                            add(buildJsonObject {
                                put("hostname", host)
                                put("service", svc)
                            })
                        }
                    }
                }
                call.respondText(AppJson.encodeToString(mapped), ContentType.Application.Json)
            }
        }

        post("/accounts/{accountId}/tunnels/{tunnelId}/routes") {
            handleApiFn(call) {
                val (store, _) = requireUnlockedFn(call)
                val accountId = call.parameters["accountId"]?.toIntOrNull()
                    ?: throw HttpError(HttpStatusCode.BadRequest, "ID akun tidak valid.")
                val tunnelId = call.parameters["tunnelId"]
                    ?: throw HttpError(HttpStatusCode.BadRequest, "Missing tunnelId")
                val payload = call.receive<CFRouteIn>()
                val acc = store.getCfAccount(accountId)
                    ?: throw HttpError(HttpStatusCode.NotFound, "Akun Cloudflare tidak ditemukan.")

                val hostname = payload.hostname.trim().lowercase()
                val service = payload.service.trim()
                val zoneId = payload.zoneId?.trim()?.ifEmpty { null }
                require(hostname.isNotBlank() && service.isNotBlank()) { "Hostname dan service wajib diisi." }

                // 1. Update ingress config on Cloudflare Tunnel
                val cfgRes = cfRequest("GET", "/accounts/${acc.accountId}/cfd_tunnel/$tunnelId/configurations", acc.token, null).jsonObject
                val config = (cfgRes["result"] as? JsonObject)?.get("config") as? JsonObject
                val currentIngress = config?.get("ingress") as? JsonArray ?: JsonArray(emptyList())

                val newIngress = mutableListOf<JsonObject>()
                var catchAll: JsonObject? = null
                for (rule in currentIngress) {
                    val rObj = rule as? JsonObject ?: continue
                    val host = rObj["hostname"]?.jsonPrimitive?.content
                    val svc = rObj["service"]?.jsonPrimitive?.content
                    if (host == null && svc == "http_status:404") {
                        catchAll = rObj
                    } else if (host != null && host.lowercase() == hostname) {
                        continue
                    } else {
                        newIngress.add(rObj)
                    }
                }
                newIngress.add(buildJsonObject {
                    put("hostname", hostname)
                    put("service", service)
                })
                catchAll?.let {
                    newIngress.add(it)
                } ?: run {
                    newIngress.add(buildJsonObject {
                        put("service", "http_status:404")
                    })
                }

                val putBody = buildJsonObject {
                    put("config", buildJsonObject {
                        put("ingress", JsonArray(newIngress))
                    })
                }
                cfRequest("PUT", "/accounts/${acc.accountId}/cfd_tunnel/$tunnelId/configurations", acc.token, AppJson.encodeToString(putBody))

                // 2. Sync DNS CNAME on the Zone if zone_id provided
                var dnsSynced = false
                var dnsWarning: String? = null
                val targetCname = "$tunnelId.cfargotunnel.com"

                if (!zoneId.isNullOrBlank()) {
                    try {
                        val dnsList = cfRequest("GET", "/zones/$zoneId/dns_records?type=CNAME&name=$hostname", acc.token, null).jsonObject
                        val records = dnsList["result"] as? JsonArray ?: JsonArray(emptyList())
                        val recordBody = buildJsonObject {
                            put("type", "CNAME")
                            put("name", hostname)
                            put("content", targetCname)
                            put("proxied", true)
                        }
                        if (records.isNotEmpty()) {
                            val recId = (records[0] as? JsonObject)?.get("id")?.jsonPrimitive?.content ?: ""
                            cfRequest("PUT", "/zones/$zoneId/dns_records/$recId", acc.token, AppJson.encodeToString(recordBody))
                        } else {
                            cfRequest("POST", "/zones/$zoneId/dns_records", acc.token, AppJson.encodeToString(recordBody))
                        }
                        dnsSynced = true
                    } catch (exc: HttpError) {
                        dnsSynced = false
                        dnsWarning = "Rute Ingress tunnel berhasil disimpan! Namun sinkronisasi DNS CNAME otomatis gagal (${exc.detail}). Token Cloudflare Anda belum memiliki izin 'Zone > DNS > Edit'. Pastikan record CNAME untuk '$hostname' diarahkan ke '$targetCname' di Cloudflare DNS."
                    } catch (exc: Exception) {
                        dnsSynced = false
                        dnsWarning = "Rute tunnel disimpan, namun pembuatan DNS CNAME otomatis gagal: ${exc.message}"
                    }
                }

                call.respondText(
                    AppJson.encodeToString(
                        buildJsonObject {
                            put("ok", true)
                            put("hostname", hostname)
                            put("service", service)
                            put("dns_synced", dnsSynced)
                            dnsWarning?.let { put("dns_warning", it) } ?: put("dns_warning", kotlinx.serialization.json.JsonNull)
                            put("cname_target", targetCname)
                        }
                    ),
                    ContentType.Application.Json
                )
            }
        }

        delete("/accounts/{accountId}/tunnels/{tunnelId}/routes") {
            handleApiFn(call) {
                val (store, _) = requireUnlockedFn(call)
                val accountId = call.parameters["accountId"]?.toIntOrNull()
                    ?: throw HttpError(HttpStatusCode.BadRequest, "ID akun tidak valid.")
                val tunnelId = call.parameters["tunnelId"]
                    ?: throw HttpError(HttpStatusCode.BadRequest, "Missing tunnelId")
                val hostnameParam = call.request.queryParameters["hostname"]
                    ?: throw HttpError(HttpStatusCode.BadRequest, "Missing hostname parameter")
                val zoneId = call.request.queryParameters["zone_id"]?.trim()?.ifEmpty { null }
                val acc = store.getCfAccount(accountId)
                    ?: throw HttpError(HttpStatusCode.NotFound, "Akun Cloudflare tidak ditemukan.")

                val hostname = hostnameParam.trim().lowercase()

                // 1. Update ingress config
                val cfgRes = cfRequest("GET", "/accounts/${acc.accountId}/cfd_tunnel/$tunnelId/configurations", acc.token, null).jsonObject
                val config = (cfgRes["result"] as? JsonObject)?.get("config") as? JsonObject
                val currentIngress = config?.get("ingress") as? JsonArray ?: JsonArray(emptyList())

                val newIngress = mutableListOf<JsonObject>()
                var catchAll: JsonObject? = null
                for (rule in currentIngress) {
                    val rObj = rule as? JsonObject ?: continue
                    val host = rObj["hostname"]?.jsonPrimitive?.content
                    val svc = rObj["service"]?.jsonPrimitive?.content
                    if (host == null && svc == "http_status:404") {
                        catchAll = rObj
                    } else if (host != null && host.lowercase() == hostname) {
                        continue
                    } else {
                        newIngress.add(rObj)
                    }
                }
                catchAll?.let {
                    newIngress.add(it)
                } ?: run {
                    newIngress.add(buildJsonObject {
                        put("service", "http_status:404")
                    })
                }

                val putBody = buildJsonObject {
                    put("config", buildJsonObject {
                        put("ingress", JsonArray(newIngress))
                    })
                }
                cfRequest("PUT", "/accounts/${acc.accountId}/cfd_tunnel/$tunnelId/configurations", acc.token, AppJson.encodeToString(putBody))

                // 2. Delete DNS CNAME if zone_id provided
                if (!zoneId.isNullOrBlank()) {
                    try {
                        val dnsList = cfRequest("GET", "/zones/$zoneId/dns_records?type=CNAME&name=$hostname", acc.token, null).jsonObject
                        val records = dnsList["result"] as? JsonArray ?: JsonArray(emptyList())
                        for (rec in records) {
                            val recId = (rec as? JsonObject)?.get("id")?.jsonPrimitive?.content
                            if (!recId.isNullOrBlank()) {
                                runCatching { cfRequest("DELETE", "/zones/$zoneId/dns_records/$recId", acc.token, null) }
                            }
                        }
                    } catch (_: Exception) {}
                }

                call.respondText(
                    AppJson.encodeToString(
                        buildJsonObject {
                            put("ok", true)
                            put("deleted", hostname)
                        }
                    ),
                    ContentType.Application.Json
                )
            }
        }

        get("/accounts/{accountId}/tunnels/{tunnelId}/token") {
            handleApiFn(call) {
                val (store, _) = requireUnlockedFn(call)
                val accountId = call.parameters["accountId"]?.toIntOrNull()
                    ?: throw HttpError(HttpStatusCode.BadRequest, "ID akun tidak valid.")
                val tunnelId = call.parameters["tunnelId"]
                    ?: throw HttpError(HttpStatusCode.BadRequest, "Missing tunnelId")
                val acc = store.getCfAccount(accountId)
                    ?: throw HttpError(HttpStatusCode.NotFound, "Akun Cloudflare tidak ditemukan.")
                val res = cfRequest("GET", "/accounts/${acc.accountId}/cfd_tunnel/$tunnelId/token", acc.token, null).jsonObject
                val tokenStr = res["result"]?.jsonPrimitive?.content ?: ""
                call.respondText(
                    AppJson.encodeToString(
                        buildJsonObject {
                            put("token", tokenStr)
                        }
                    ),
                    ContentType.Application.Json
                )
            }
        }
    }
}
