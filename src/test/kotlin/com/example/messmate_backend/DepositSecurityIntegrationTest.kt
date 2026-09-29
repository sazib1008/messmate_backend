package com.example.messmate_backend

import com.example.messmate_backend.dto.*
import com.example.messmate_backend.model.enums.DepositStatus
import com.example.messmate_backend.model.enums.PaymentMethod
import com.example.messmate_backend.repository.DepositRepository
import com.example.messmate_backend.repository.MessRepository
import com.example.messmate_backend.repository.UserRepository
import com.example.messmate_backend.service.DepositService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DepositSecurityIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var depositService: DepositService

    @Autowired
    private lateinit var depositRepository: DepositRepository

    @Autowired
    private lateinit var messRepository: MessRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .findAndRegisterModules()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val httpClient = HttpClient.newHttpClient()

    private fun baseUrl(path: String) = "http://localhost:$port$path"

    private fun getAuthToken(email: String): String {
        val loginReq = LoginRequest(email = email, password = "password123")
        val req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/auth/login")))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(loginReq)))
            .build()

        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, resp.statusCode(), "Login should succeed for $email")
        val root = objectMapper.readTree(resp.body())
        return root.get("token").asText()
    }

    @Test
    fun testStudentCanCreateDepositForSelf() {
        val studentToken = getAuthToken("s1.arafat@messmate.com")
        val studentUser = userRepository.findByEmail("s1.arafat@messmate.com").orElseThrow()

        val depositReq = CreateDepositRequest(
            amount = BigDecimal("1500.00"),
            paymentMethod = PaymentMethod.BKASH,
            transactionRef = "BKASH12345",
            notes = "Student advance payment"
        )

        val req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/deposits")))
            .header("Authorization", "Bearer $studentToken")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(depositReq)))
            .build()

        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(201, resp.statusCode(), "Student should be able to create deposit request")

        val root = objectMapper.readTree(resp.body())
        assertEquals(studentUser.id, root.get("userId").asText(), "Deposit must belong to the authenticated student")
        assertEquals(DepositStatus.PENDING.name, root.get("status").asText(), "New deposit must be in PENDING status")
    }

    @Test
    fun testStudentCannotForgeDepositorIdentityInBody() {
        val studentToken = getAuthToken("s1.arafat@messmate.com")
        val studentUser = userRepository.findByEmail("s1.arafat@messmate.com").orElseThrow()
        val victimUser = userRepository.findByEmail("s2.bilal@messmate.com").orElseThrow()

        // Attempting to send someone else's userId in the payload
        val maliciousJson = """
            {
                "userId": "${victimUser.id}",
                "memberId": "${victimUser.id}",
                "amount": 2500.00,
                "paymentMethod": "NAGAD",
                "transactionRef": "HACKED999",
                "notes": "Attempting privilege escalation"
            }
        """.trimIndent()

        val req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/deposits")))
            .header("Authorization", "Bearer $studentToken")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(maliciousJson))
            .build()

        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(201, resp.statusCode())

        val root = objectMapper.readTree(resp.body())
        assertEquals(studentUser.id, root.get("userId").asText(), "Server MUST ignore client-supplied identity and credit authenticated student only")
        assertNotEquals(victimUser.id, root.get("userId").asText(), "Victim user must not be credited")
    }

    @Test
    fun testStudentCannotApproveOrRejectDepositViaHttp() {
        val studentToken = getAuthToken("s1.arafat@messmate.com")

        // 1. Direct review endpoint
        val reviewReq = ReviewDepositRequest(approved = true)
        val reviewHttp = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/deposits/dummy-deposit-id/review")))
            .header("Authorization", "Bearer $studentToken")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(reviewReq)))
            .build()
        val reviewResp = httpClient.send(reviewHttp, HttpResponse.BodyHandlers.ofString())
        assertEquals(403, reviewResp.statusCode(), "Student POST /api/deposits/{id}/review must return 403 Forbidden")

        // 2. Convenience approve endpoint
        val approveHttp = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/deposits/dummy-deposit-id/approve")))
            .header("Authorization", "Bearer $studentToken")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val approveResp = httpClient.send(approveHttp, HttpResponse.BodyHandlers.ofString())
        assertEquals(403, approveResp.statusCode(), "Student POST /api/deposits/{id}/approve must return 403 Forbidden")

        // 3. Convenience reject endpoint
        val rejectHttp = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/deposits/dummy-deposit-id/reject")))
            .header("Authorization", "Bearer $studentToken")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val rejectResp = httpClient.send(rejectHttp, HttpResponse.BodyHandlers.ofString())
        assertEquals(403, rejectResp.statusCode(), "Student POST /api/deposits/{id}/reject must return 403 Forbidden")
    }

    @Test
    fun testStudentCannotEditOrDeleteDepositViaHttp() {
        val studentToken = getAuthToken("s1.arafat@messmate.com")

        // Student tries to edit deposit
        val updateReq = CreateDepositRequest(
            amount = BigDecimal("9999.00"),
            paymentMethod = PaymentMethod.CASH,
            notes = "Attempting to inflate deposit"
        )
        val putHttp = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/deposits/dummy-deposit-id")))
            .header("Authorization", "Bearer $studentToken")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(updateReq)))
            .build()
        val putResp = httpClient.send(putHttp, HttpResponse.BodyHandlers.ofString())
        assertEquals(403, putResp.statusCode(), "Student PUT /api/deposits/{id} must return 403 Forbidden")

        // Student tries to delete deposit
        val deleteHttp = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/deposits/dummy-deposit-id")))
            .header("Authorization", "Bearer $studentToken")
            .DELETE()
            .build()
        val deleteResp = httpClient.send(deleteHttp, HttpResponse.BodyHandlers.ofString())
        assertEquals(403, deleteResp.statusCode(), "Student DELETE /api/deposits/{id} must return 403 Forbidden")
    }

    @Test
    fun testStudentDepositReadVisibilityModelIsOwnOnly() {
        val studentToken = getAuthToken("s1.arafat@messmate.com")
        val studentUser = userRepository.findByEmail("s1.arafat@messmate.com").orElseThrow()
        val mess = messRepository.findAll().firstOrNull()
        val messId = mess?.id ?: "greenfield-hall"

        // Student calls GET /api/deposits
        val req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/deposits?messId=$messId")))
            .header("Authorization", "Bearer $studentToken")
            .GET()
            .build()

        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, resp.statusCode(), "Student GET /api/deposits must return 200 OK")

        val root = objectMapper.readTree(resp.body())
        assertTrue(root.isArray)
        // Verify every deposit in the returned array belongs to this student only
        for (i in 0 until root.size()) {
            val dep = root.get(i)
            assertEquals(studentUser.id, dep.get("userId").asText(), "Returned deposits must belong ONLY to the requesting student")
        }
    }

    @Test
    fun testManagerCanReviewUpdateAndDeleteDepositViaHttp() {
        val managerToken = getAuthToken("primary.manager@messmate.com")
        val studentToken = getAuthToken("s1.arafat@messmate.com")

        // 1. Student submits a deposit
        val depositReq = CreateDepositRequest(
            amount = BigDecimal("800.00"),
            paymentMethod = PaymentMethod.BKASH,
            transactionRef = "TRX889900",
            notes = "Advance deposit for test"
        )
        val submitHttp = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/deposits")))
            .header("Authorization", "Bearer $studentToken")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(depositReq)))
            .build()
        val submitResp = httpClient.send(submitHttp, HttpResponse.BodyHandlers.ofString())
        assertEquals(201, submitResp.statusCode())
        val depositId = objectMapper.readTree(submitResp.body()).get("id").asText()

        // 2. Manager updates deposit metadata only (NOT amount — amount is immutable after student submission)
        // If the amount is wrong, the manager must REJECT and ask the student to resubmit.
        val updateReq = UpdateDepositRequest(
            paymentMethod = PaymentMethod.BKASH,
            transactionRef = "TRX889900-VERIFIED",
            notes = "Manager verified receipt"
        )
        val updateHttp = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/deposits/$depositId")))
            .header("Authorization", "Bearer $managerToken")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(updateReq)))
            .build()
        val updateResp = httpClient.send(updateHttp, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, updateResp.statusCode(), "Manager should be able to update deposit metadata")
        // Amount must remain the original student-submitted 800.00 — manager cannot change it
        assertEquals(0, BigDecimal("800.00").compareTo(BigDecimal(objectMapper.readTree(updateResp.body()).get("amount").asText())),
            "Amount must remain the student's original submission value — manager amount editing is disallowed")
        assertEquals("TRX889900-VERIFIED", objectMapper.readTree(updateResp.body()).get("transactionRef").asText(),
            "Manager should be able to update transactionRef")

        // 3. Manager approves deposit
        val approveHttp = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/deposits/$depositId/approve")))
            .header("Authorization", "Bearer $managerToken")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val approveResp = httpClient.send(approveHttp, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, approveResp.statusCode(), "Manager should be able to approve deposit")
        assertEquals(DepositStatus.APPROVED.name, objectMapper.readTree(approveResp.body()).get("status").asText())

        // 4. Manager deletes deposit
        val deleteHttp = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/deposits/$depositId")))
            .header("Authorization", "Bearer $managerToken")
            .DELETE()
            .build()
        val deleteResp = httpClient.send(deleteHttp, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, deleteResp.statusCode(), "Manager should be able to delete deposit")
    }
}
