package id.termdeck.app

import id.termdeck.app.server.AppJson
import id.termdeck.app.server.normalizeContainerJson
import id.termdeck.app.server.parsePortsJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainersNormalizationTest {

    @Test
    fun `handles podman container json with array command`() {
        val podmanRaw = """
        {
            "Id": "16230df461245964d4cf7b8da07fc3d715650b4da6cada79f09088b85c699b3c",
            "Names": ["test_dummy"],
            "Image": "docker.io/library/alpine:latest",
            "State": "running",
            "Status": "Up Less than a second",
            "Created": 1793192321,
            "CreatedAt": "Less than a second ago",
            "Command": ["sleep", "30"],
            "Ports": [
                {
                    "host_ip": "127.0.0.1",
                    "host_port": 8080,
                    "container_port": 80,
                    "protocol": "tcp"
                }
            ]
        }
        """.trimIndent()

        val rawObj = AppJson.parseToJsonElement(podmanRaw).jsonObject
        val normalized = normalizeContainerJson(rawObj)

        assertEquals("16230df46124", (normalized["id"] as? JsonPrimitive)?.content)
        assertEquals("sleep 30", (normalized["command"] as? JsonPrimitive)?.content)
        assertEquals("running", (normalized["state"] as? JsonPrimitive)?.content)
        val ports = normalized["ports"] as JsonArray
        assertEquals(1, ports.size)
        assertEquals("8080", (ports[0].jsonObject["host_port"] as? JsonPrimitive)?.content)
    }

    @Test
    fun `handles docker container json with string command`() {
        val dockerRaw = """
        {
            "ID": "abc123def456",
            "Names": "/my_container",
            "Image": "nginx:alpine",
            "State": "running",
            "Status": "Up 2 hours",
            "CreatedAt": "2026-10-28 10:00:00",
            "Command": "\"nginx -g 'daemon off;'\"",
            "Ports": "0.0.0.0:80->80/tcp"
        }
        """.trimIndent()

        val rawObj = AppJson.parseToJsonElement(dockerRaw).jsonObject
        val normalized = normalizeContainerJson(rawObj)

        assertEquals("abc123def456", (normalized["id"] as? JsonPrimitive)?.content)
        assertEquals("\"nginx -g 'daemon off;'\"", (normalized["command"] as? JsonPrimitive)?.content)
        assertEquals("running", (normalized["state"] as? JsonPrimitive)?.content)
    }

    @Test
    fun `handles container with object state and null command`() {
        val objStateRaw = """
        {
            "Id": "123456789012345",
            "Names": ["web"],
            "Image": "redis:latest",
            "State": { "Status": "running" },
            "Status": "Up",
            "Command": null
        }
        """.trimIndent()

        val rawObj = AppJson.parseToJsonElement(objStateRaw).jsonObject
        val normalized = normalizeContainerJson(rawObj)

        assertEquals("123456789012", (normalized["id"] as? JsonPrimitive)?.content)
        assertEquals("", (normalized["command"] as? JsonPrimitive)?.content)
        assertEquals("running", (normalized["state"] as? JsonPrimitive)?.content)
    }

    @Test
    fun `handles container with null ports and exposed ports object`() {
        val podmanExposedRaw = """
        {
            "Id": "1076caca2d7536d6cf4972ec69ca915f821289d9a3c14be0045f1ff1e5e94389",
            "Names": ["test_termdeck_debug"],
            "Image": "docker.io/library/nginx:1.27-alpine",
            "State": "running",
            "Status": "Up Less than a second",
            "Created": 1793193641,
            "Ports": null,
            "ExposedPorts": {
                "80": ["tcp"]
            },
            "Command": ["sleep", "60"]
        }
        """.trimIndent()

        val rawObj = AppJson.parseToJsonElement(podmanExposedRaw).jsonObject
        val normalized = normalizeContainerJson(rawObj)

        assertEquals("1076caca2d75", (normalized["id"] as? JsonPrimitive)?.content)
        val ports = normalized["ports"] as JsonArray
        assertEquals(1, ports.size)
        assertEquals("80", (ports[0].jsonObject["container_port"] as? JsonPrimitive)?.content)
    }

    @Test
    fun `parsePortsJson handles string arrays and object mapping safely`() {
        val stringArr = AppJson.parseToJsonElement("""["0.0.0.0:8080->80/tcp", "443/tcp"]""")
        val parsedArr = parsePortsJson(stringArr)
        assertEquals(2, parsedArr.size)

        val objMap = AppJson.parseToJsonElement("""{"80/tcp": [{"HostIp": "127.0.0.1", "HostPort": 8080}]}""")
        val parsedMap = parsePortsJson(objMap)
        assertEquals(1, parsedMap.size)
        assertEquals("127.0.0.1", (parsedMap[0].jsonObject["host_ip"] as? JsonPrimitive)?.content)
        assertEquals("8080", (parsedMap[0].jsonObject["host_port"] as? JsonPrimitive)?.content)
        assertEquals("80", (parsedMap[0].jsonObject["container_port"] as? JsonPrimitive)?.content)
    }
}

