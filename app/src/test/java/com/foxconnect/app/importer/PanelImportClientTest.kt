package com.foxconnect.app.importer

import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelImportClientTest {
    private val vless = "vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls&sni=example.com#panel"

    @Test
    fun rejectsNonHttpsBeforeMakingARequest() = runBlocking {
        val transport = RecordingTransport { error("must not execute") }
        val request = request(PanelProvider.MARZBAN, "http://panel.example")

        val result = try {
            PanelImportClient(transport).fetch(request)
        } finally {
            request.clear()
        }

        assertEquals(
            PanelImportResult.Failure(PanelImportFailureReason.INVALID_INPUT),
            result,
        )
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun marzbanUsesTokenAndBoundedUsersResponseWithoutFetchingSubscriptionUrls() = runBlocking {
        val transport = RecordingTransport { request ->
            when (request.url.path) {
                "/prefix/api/admin/token" -> response(200, "{\"access_token\":\"0123456789abcdef0123456789abcdef\"}")
                "/prefix/api/users" -> response(
                    200,
                    """{"users":[{"links":["$vless"],"subscription_url":"https://sub.example/secret"}],"total":1}""",
                )
                else -> error("unexpected request")
            }
        }
        val request = request(PanelProvider.MARZBAN, "https://panel.example/prefix")

        val result = try {
            PanelImportClient(transport).fetch(request)
        } finally {
            request.clear()
        }

        assertTrue(result is PanelImportResult.Success)
        assertEquals(2, transport.requests.size)
        assertEquals("Bearer 0123456789abcdef0123456789abcdef", transport.requests[1].headers["Authorization"])
        assertFalse(transport.requests.any { it.url.host == "sub.example" })
    }

    @Test
    fun pasarguardFetchesReturnedHttpsSubscriptionWithoutForwardingBearerToken() = runBlocking {
        val transport = RecordingTransport { request ->
            when (request.url.host to request.url.path) {
                "panel.example" to "/api/admin/token" -> response(200, "{\"access_token\":\"0123456789abcdef\"}")
                "panel.example" to "/api/users" -> response(
                    200,
                    """{"users":[{"subscription_url":"https://sub.example/config"}],"total":1}""",
                )
                "sub.example" to "/config" -> response(200, vless)
                else -> error("unexpected request")
            }
        }
        val request = request(PanelProvider.PASARGUARD, "https://panel.example")

        val result = try {
            PanelImportClient(transport).fetch(request)
        } finally {
            request.clear()
        }

        assertTrue(result is PanelImportResult.Success)
        val subscriptionRequest = transport.requests.single { it.url.host == "sub.example" }
        assertFalse(subscriptionRequest.headers.containsKey("Authorization"))
    }

    @Test
    fun hiddifySupportsDocumentedBasicUserPath() = runBlocking {
        val transport = RecordingTransport { request ->
            assertTrue(request.headers["Authorization"].orEmpty().startsWith("Basic "))
            response(200, vless)
        }
        val request = request(PanelProvider.HIDDIFY, "https://panel.example/client/user-id")

        val result = try {
            PanelImportClient(transport).fetch(request)
        } finally {
            request.clear()
        }

        assertTrue(result is PanelImportResult.Success)
        assertEquals("/client/user-id/sub", transport.requests.single().url.path)
    }

    @Test
    fun hiddifyAdminCredentialsAreNotForwardedToUuidSubscriptions() = runBlocking {
        val userId = "22222222-2222-4222-8222-222222222222"
        val transport = RecordingTransport { request ->
            when (request.url.path) {
                "/admin-secret/api/v2/admin/user/" -> response(200, "[{\"uuid\":\"$userId\",\"name\":\"u\"}]")
                "/client-secret/$userId/sub" -> response(200, vless)
                else -> error("unexpected request")
            }
        }
        val request = PanelImportRequest(
            provider = PanelProvider.HIDDIFY,
            serverUrl = "https://panel.example/admin-secret",
            username = "admin-name",
            password = "admin-password".toCharArray(),
            hiddifyClientPath = "client-secret",
        )

        val result = try {
            PanelImportClient(transport).fetch(request)
        } finally {
            request.clear()
        }

        assertTrue(result is PanelImportResult.Success)
        assertEquals(2, transport.requests.size)
        val userSubscription = transport.requests.single { it.url.path.contains(userId) }
        assertFalse(userSubscription.headers.containsKey("Authorization"))
    }

    @Test
    fun partialSubscriptionFailuresAreCountedAndTheirBodiesAreWiped() = runBlocking {
        val failedBody = "private-upstream-error".toByteArray(StandardCharsets.UTF_8)
        val transport = RecordingTransport { request ->
            when (request.url.host to request.url.path) {
                "panel.example" to "/api/admin/token" -> response(200, "{\"access_token\":\"0123456789abcdef\"}")
                "panel.example" to "/api/users" -> response(
                    200,
                    """{"users":[{"subscription_url":"https://sub.example/failed"},{"subscription_url":"https://sub.example/ok"}],"total":2}""",
                )
                "sub.example" to "/failed" -> PanelHttpResult.Response(PanelHttpResponse(404, failedBody))
                "sub.example" to "/ok" -> response(200, vless)
                else -> error("unexpected request")
            }
        }
        val request = request(PanelProvider.PASARGUARD, "https://panel.example")

        val result = try {
            PanelImportClient(transport).fetch(request)
        } finally {
            request.clear()
        }

        assertTrue(result is PanelImportResult.Success)
        assertEquals(1, (result as PanelImportResult.Success).skippedSources)
        assertTrue(failedBody.all { it == 0.toByte() })
    }

    @Test
    fun unexpectedTransportErrorsBecomeSanitizedNetworkFailures() = runBlocking {
        val transport = RecordingTransport { error("https://private.example/token?secret=value") }
        val request = request(PanelProvider.MARZBAN, "https://panel.example")

        val result = try {
            PanelImportClient(transport).fetch(request)
        } finally {
            request.clear()
        }

        assertEquals(PanelImportResult.Failure(PanelImportFailureReason.NETWORK), result)
        assertFalse(result.toString().contains("private.example"))
        assertFalse(result.toString().contains("secret"))
    }

    @Test
    fun coroutineCancellationIsNeverConvertedToAnImportFailure() = runBlocking {
        val transport = RecordingTransport { throw CancellationException("cancel import") }
        val request = request(PanelProvider.MARZBAN, "https://panel.example")

        val thrown = try {
            runCatching { PanelImportClient(transport).fetch(request) }.exceptionOrNull()
        } finally {
            request.clear()
        }

        assertTrue(thrown is CancellationException)
    }

    @Test
    fun secretBearingTypesHaveRedactedStringRepresentations() {
        val request = PanelImportRequest(
            PanelProvider.MARZBAN,
            "https://private.example/hidden",
            "private-user",
            "private-password".toCharArray(),
        )
        val http = PanelHttpRequest(
            url = java.net.URI("https://private.example/hidden"),
            headers = mapOf("Authorization" to "Bearer private-token"),
            body = "private-password".toByteArray(),
            maxResponseBytes = 10,
        )
        try {
            val rendered = request.toString() + http.toString() + PanelHttpResponse(200, vless.toByteArray())
            assertFalse(rendered.contains("private.example"))
            assertFalse(rendered.contains("private-user"))
            assertFalse(rendered.contains("private-password"))
            assertFalse(rendered.contains("private-token"))
            assertFalse(rendered.contains(vless))
        } finally {
            request.clear()
        }
    }

    private fun request(provider: PanelProvider, url: String) = PanelImportRequest(
        provider = provider,
        serverUrl = url,
        username = "admin",
        password = "password".toCharArray(),
    )

    private fun response(status: Int, body: String) = PanelHttpResult.Response(
        PanelHttpResponse(status, body.toByteArray(StandardCharsets.UTF_8)),
    )

    private class RecordingTransport(
        private val handler: suspend (PanelHttpRequest) -> PanelHttpResult,
    ) : PanelHttpTransport {
        val requests = mutableListOf<PanelHttpRequest>()

        override suspend fun execute(request: PanelHttpRequest): PanelHttpResult {
            requests += request
            return handler(request)
        }
    }
}
