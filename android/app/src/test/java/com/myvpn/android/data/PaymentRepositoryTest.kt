package com.myvpn.android.data

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PaymentRepositoryTest {
    @Test fun tariffsUseJwtAndDecodeExactBackendFields() = runBlocking {
        val server = MockWebServer()
        server.enqueue(json("""[{"id":"id","code":"backend-code","name":"Backend name","description":null,"durationDays":42,"price":123.45,"currency":"RUB"}]"""))
        server.start()
        try {
            val item = repository(server).tariffs().single()
            assertEquals("backend-code", item.code)
            assertEquals(42, item.durationDays)
            assertEquals(BigDecimal("123.45"), item.price)
            val request = server.takeRequest()
            assertEquals("/api/v1/tariffs", request.path)
            assertEquals("Bearer access", request.getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun checkoutAndCurrentMatchWireContract() = runBlocking {
        val server = MockWebServer()
        server.enqueue(json("""{"paymentOrderId":"order","tariffName":"Backend name","amount":123.45,"currency":"RUB","durationDays":42,"status":"PENDING","confirmationUrl":"https://checkout.example.test/pay","expiresAt":"2026-09-14T00:00:00Z"}"""))
        server.enqueue(json("""{"paymentOrderId":"order","outcome":"SUCCEEDED","paymentStatus":"SUCCEEDED","activationStatus":"ACTIVATED","paidAt":"2026-09-13T00:00:00Z","nextCheckAt":null,"expiresAt":null}"""))
        server.start()
        try {
            val repository = repository(server)
            val checkout = repository.checkout("backend-code")
            assertEquals(BigDecimal("123.45"), checkout.amount)
            assertEquals("https://checkout.example.test/pay", checkout.confirmationUrl)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/payments/checkout", request.path)
            assertEquals("""{"tariffCode":"backend-code"}""", request.body.readUtf8())
            assertEquals("Bearer access", request.getHeader("Authorization"))
            assertEquals("ACTIVATED", repository.current().activationStatus)
            val current = server.takeRequest()
            assertEquals("/api/v1/payments/current", current.path)
            assertEquals("Bearer access", current.getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun unauthorizedPaymentRequestUsesExistingRefreshAndRetriesOnce() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(json("""{"accessToken":"new-access","refreshToken":"new-refresh","expiresIn":3600}"""))
        server.enqueue(json("""{"outcome":"NOT_FOUND","paymentOrderId":null,"paymentStatus":null,"activationStatus":null,"paidAt":null,"nextCheckAt":null,"expiresAt":null}"""))
        server.start()
        try {
            assertEquals("NOT_FOUND", repository(server).current().outcome)
            assertEquals("/api/v1/payments/current", server.takeRequest().path)
            assertEquals("/api/v1/auth/refresh", server.takeRequest().path)
            assertEquals("Bearer new-access", server.takeRequest().getHeader("Authorization"))
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    private fun repository(server: MockWebServer): PaymentRepository {
        val api = ApiFactory.create(server.url("/").toString())
        val store = object : SessionStore {
            var saved = Session("access", "refresh", 3600, "account", "EXPIRED", null, Long.MAX_VALUE)
            override suspend fun session() = saved
            override suspend fun save(session: Session) { saved = session }
            override suspend fun clear(reason: SessionClearReason) = Unit
        }
        return PaymentRepository(api, PhoneAuthRepository(api, store))
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
