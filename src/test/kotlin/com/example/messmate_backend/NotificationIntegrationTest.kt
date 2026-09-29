package com.example.messmate_backend

import com.example.messmate_backend.repository.DeviceTokenRepository
import com.example.messmate_backend.repository.UserRepository
import com.example.messmate_backend.security.JwtTokenProvider
import com.example.messmate_backend.service.DeviceTokenRegistrationDto
import com.example.messmate_backend.service.NotificationService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var notificationService: NotificationService

    @Autowired
    private lateinit var deviceTokenRepository: DeviceTokenRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    private val httpClient = HttpClient.newHttpClient()
    private val objectMapper = ObjectMapper()

    @Test
    @Transactional
    fun testDeviceTokenRegistrationAndDeduplication() {
        val testUser = userRepository.findAll().firstOrNull()
        assertNotNull(testUser, "Test user must exist")
        val userId = testUser!!.id
        val userEmail = testUser.email

        val testToken1 = "fcm_test_token_" + UUID.randomUUID().toString()

        // 1. Register first token
        notificationService.registerDeviceToken(
            identifier = userEmail,
            dto = DeviceTokenRegistrationDto(token = testToken1, platform = "ANDROID")
        )

        val tokensAfterFirst = notificationService.getTokensForUser(userId)
        assertTrue(tokensAfterFirst.contains(testToken1), "Token 1 should be stored for user")

        // 2. Register the exact same token again (simulating re-login or app restart)
        notificationService.registerDeviceToken(
            identifier = userEmail,
            dto = DeviceTokenRegistrationDto(token = testToken1, platform = "ANDROID")
        )

        // Verify deduplication: no duplicate row exists for testToken1
        val allUserTokens = deviceTokenRepository.findAllByUserId(userId).filter { it.token == testToken1 }
        assertEquals(1, allUserTokens.size, "Deduplication failed: token registered multiple times created duplicate rows")

        // 3. Register a second device for same user (e.g. tablet)
        val testToken2 = "fcm_test_token_tablet_" + UUID.randomUUID().toString()
        notificationService.registerDeviceToken(
            identifier = userEmail,
            dto = DeviceTokenRegistrationDto(token = testToken2, platform = "ANDROID")
        )

        val userTokensMulti = notificationService.getTokensForUser(userId)
        assertTrue(userTokensMulti.contains(testToken1), "User should have token 1")
        assertTrue(userTokensMulti.contains(testToken2), "User should have token 2")

        // 4. Test simulated push send (guaranteed non-fatal)
        assertDoesNotThrow {
            notificationService.sendPushToUser(
                userId = userId,
                title = "Test Notification",
                body = "Test body",
                data = mapOf("screen" to "wallet", "type" to "DEPOSIT_APPROVED")
            )
        }

        // 5. Unregister token
        notificationService.unregisterDeviceToken(testToken1)
        val tokensAfterDelete = notificationService.getTokensForUser(userId)
        assertFalse(tokensAfterDelete.contains(testToken1), "Token 1 should be deleted after unregistration")
        assertTrue(tokensAfterDelete.contains(testToken2), "Token 2 should still remain")

        // Clean up token 2
        notificationService.unregisterDeviceToken(testToken2)
    }

    @Test
    fun testHttpEndpointRegistrationViaJwtAndDeduplication() {
        val testUser = userRepository.findAll().firstOrNull()
        assertNotNull(testUser, "Test user must exist")
        val userId = testUser!!.id
        val userEmail = testUser.email

        val jwt = jwtTokenProvider.generateToken(userEmail, userId, "STUDENT")
        val httpToken = "fcm_http_token_" + UUID.randomUUID().toString()

        // 1. POST /api/notifications/device-token with JWT over HTTP
        val request1 = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/notifications/device-token"))
            .header("Authorization", "Bearer $jwt")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"token":"$httpToken","platform":"ANDROID"}"""))
            .build()

        val response1 = httpClient.send(request1, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response1.statusCode(), "Expected HTTP 200 from device-token registration")

        val body1 = objectMapper.readTree(response1.body())
        assertEquals("success", body1.get("status").asText())

        // Confirm row created in DB
        val tokensInDb = deviceTokenRepository.findAllByUserId(userId).filter { it.token == httpToken }
        assertEquals(1, tokensInDb.size, "Database should contain exactly 1 row for the registered token")

        // 2. POST the exact same token again with same JWT (re-registration)
        val request2 = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/notifications/device-token"))
            .header("Authorization", "Bearer $jwt")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"token":"$httpToken","platform":"ANDROID"}"""))
            .build()

        val response2 = httpClient.send(request2, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response2.statusCode())

        // Confirm still only 1 row exists (deduplication confirmed)
        val tokensAfterDuplicateCall = deviceTokenRepository.findAllByUserId(userId).filter { it.token == httpToken }
        assertEquals(1, tokensAfterDuplicateCall.size, "Deduplication failed on HTTP endpoint: duplicate token row was inserted")

        // 3. DELETE /api/notifications/device-token
        val request3 = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/notifications/device-token?token=$httpToken"))
            .header("Authorization", "Bearer $jwt")
            .DELETE()
            .build()

        val response3 = httpClient.send(request3, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response3.statusCode())

        // Confirm row deleted
        val tokensAfterDelete = deviceTokenRepository.findAllByUserId(userId).filter { it.token == httpToken }
        assertEquals(0, tokensAfterDelete.size, "Token row should be deleted from DB")
    }
}
