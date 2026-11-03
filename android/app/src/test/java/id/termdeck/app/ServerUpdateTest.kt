package id.termdeck.app

import id.termdeck.app.data.LoginOut
import id.termdeck.app.data.SetupPinIn
import id.termdeck.app.data.Vault
import id.termdeck.app.server.AppJson
import id.termdeck.app.server.configureTermDeckModule
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ServerUpdateTest {

    @Test
    fun testUpdateCheckUnauthorizedWhenLocked() = testApplication {
        val tempFolder = File.createTempFile("test-vault", "dir").apply { delete(); mkdirs() }
        val vault = Vault(tempFolder)
        val token = "test-token"

        application {
            configureTermDeckModule(vault = vault, token = token, allowedHosts = null)
        }

        val response = client.get("/api/system/update-check") {
            header("X-Token", token)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        tempFolder.deleteRecursively()
    }

    @Test
    fun testUpdateCheckWhenUnlocked() = testApplication {
        val tempFolder = File.createTempFile("test-vault", "dir").apply { delete(); mkdirs() }
        val fakePool = FakeSshPool()
        val vault = Vault(
            folder = tempFolder,
            storeFactory = { f, k -> InMemoryStore(f, k) },
            poolFactory = { fakePool }
        )
        val token = "test-token"

        application {
            configureTermDeckModule(vault = vault, token = token, allowedHosts = null)
        }

        // Setup PIN to initialize vault
        val setupRes = client.post("/api/auth/setup-pin") {
            header("X-Token", token)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(SetupPinIn(pin = "123456")))
        }
        assertEquals(HttpStatusCode.OK, setupRes.status)
        val session = AppJson.decodeFromString<LoginOut>(setupRes.bodyAsText()).session

        // Check update route
        val checkRes = client.get("/api/system/update-check") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertEquals(HttpStatusCode.OK, checkRes.status)
        val bodyText = checkRes.bodyAsText()
        assertTrue(bodyText.contains("\"platform\":\"android\""))

        // Check progress route
        val progRes = client.get("/api/system/update-progress") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertEquals(HttpStatusCode.OK, progRes.status)
        val progBody = progRes.bodyAsText()
        assertTrue(progBody.contains("\"state\":\"idle\""))

        tempFolder.deleteRecursively()
    }
}
