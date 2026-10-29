package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.WebAccessRequest
import com.tlisonbee.mediasync.api.WebTicket
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebAccessTest {
    private suspend fun TestDevice.ticket() = post("/web/ticket", emptyMap<String, String>())

    private suspend fun HttpClient.loginWith(ticket: String, next: String = "/") =
        submitForm("/login/device", parameters { append("ticket", ticket); append("next", next) })

    @Test
    fun theAppIsLetInOnceWithThePasswordThenByTicket() = withServer {
        val phone = pair("Phone", DeviceKind.ANDROID)
        assertEquals(HttpStatusCode.Forbidden, phone.ticket().status, "a paired device isn't let in by its token alone")

        // A wrong password is 403, so the app doesn't take it for a rejected token.
        assertEquals(HttpStatusCode.Forbidden, phone.post("/web/access", WebAccessRequest("wrong")).status)
        assertEquals(HttpStatusCode.NoContent, phone.post("/web/access", WebAccessRequest(ADMIN_PASSWORD)).status)

        val ticket = phone.ticket().body<WebTicket>().ticket
        val web = browser()
        val login = web.loginWith(ticket, next = "/videos")
        assertEquals(HttpStatusCode.Found, login.status)
        assertEquals("/videos", login.headers[HttpHeaders.Location])
        assertEquals(HttpStatusCode.OK, web.get("/").status)

        // A ticket works once.
        val again = browser()
        assertEquals("/login", again.loginWith(ticket).headers[HttpHeaders.Location])
        assertEquals(HttpStatusCode.Found, again.get("/").status)
        assertEquals("/login", browser().loginWith("made-up").headers[HttpHeaders.Location])

        // A login only ever goes to a page of this server.
        val elsewhere = browser().loginWith(phone.ticket().body<WebTicket>().ticket, next = "//evil.example/")
        assertEquals("/", elsewhere.headers[HttpHeaders.Location])

        // Other devices are still sync-only.
        assertEquals(HttpStatusCode.Forbidden, pair("Laptop").ticket().status)
    }

    @Test
    fun aDownloadLinkNamesTheFile() = withServer {
        val pc = pair("PC")
        val photo = TestDevice.file("beach.png", PhotosTest.png(1))
        pc.upload(photo)
        val admin = browser().also { it.submitForm("/login", parameters { append("password", ADMIN_PASSWORD) }) }
        val saved = admin.get("/web/blobs/${photo.blobHash}?download=Beach%20day.png")
        assertEquals("attachment; filename=\"Beach day.png\"", saved.headers[HttpHeaders.ContentDisposition])
        assertEquals(null, admin.get("/web/blobs/${photo.blobHash}").headers[HttpHeaders.ContentDisposition], "shown in place otherwise")
    }

    @Test
    fun revokingWebAccessEndsTheAppsSession() = withServer {
        val phone = pair("Phone", DeviceKind.ANDROID)
        phone.post("/web/access", WebAccessRequest(ADMIN_PASSWORD))
        val app = browser().also { it.loginWith(phone.ticket().body<WebTicket>().ticket) }
        val admin = browser().also {
            assertEquals(HttpStatusCode.Found, it.submitForm("/login", parameters { append("password", ADMIN_PASSWORD) }).status)
        }
        val devices = admin.get("/devices").bodyAsText()
        assertTrue("Revoke web access" in devices)
        assertTrue("/devices/${phone.id}/web-access/revoke" in devices)

        admin.submitForm("/devices/${phone.id}/web-access/revoke", parameters { })
        assertEquals(HttpStatusCode.Found, app.get("/").status, "the app's web view is logged out")
        assertEquals(HttpStatusCode.OK, admin.get("/").status, "a password login isn't")
        assertEquals(HttpStatusCode.Forbidden, phone.ticket().status)
        assertFalse("Revoke web access" in admin.get("/devices").bodyAsText())
        // Syncing carries on.
        assertEquals(HttpStatusCode.OK, phone.get("/changes?since=0").status)
    }

    @Test
    fun changingThePasswordOrRevokingTheDeviceEndsAccess() = withServer {
        val phone = pair("Phone", DeviceKind.ANDROID)
        val tablet = pair("Tablet", DeviceKind.ANDROID)
        for (d in listOf(phone, tablet)) d.post("/web/access", WebAccessRequest(ADMIN_PASSWORD))
        val app = browser().also { it.loginWith(tablet.ticket().body<WebTicket>().ticket) }

        repo.revoke(tablet.id)
        assertEquals(HttpStatusCode.Found, app.get("/").status, "a revoked device's web view is logged out")

        assertEquals(HttpStatusCode.OK, phone.ticket().status)
        Setup.changePassword(repo.dir, "a different long password")
        assertEquals(HttpStatusCode.Forbidden, phone.ticket().status, "a new password ends every phone's access")
        assertEquals(HttpStatusCode.NoContent, phone.post("/web/access", WebAccessRequest("a different long password")).status)
        assertEquals(HttpStatusCode.OK, phone.ticket().status)
    }
}
