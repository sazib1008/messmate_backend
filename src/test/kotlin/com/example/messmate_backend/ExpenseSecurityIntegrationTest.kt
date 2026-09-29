package com.example.messmate_backend

import com.example.messmate_backend.dto.AuthResponse
import com.example.messmate_backend.dto.CreateExpenseRequest
import com.example.messmate_backend.dto.ExpenseDto
import com.example.messmate_backend.dto.LoginRequest
import com.example.messmate_backend.model.enums.ExpenseCategory
import com.example.messmate_backend.repository.ExpenseRepository
import com.example.messmate_backend.repository.MessRepository
import com.example.messmate_backend.repository.UserRepository
import com.example.messmate_backend.service.ExpenseService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
import java.time.LocalDate

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExpenseSecurityIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var expenseService: ExpenseService

    @Autowired
    private lateinit var expenseRepository: ExpenseRepository

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
    fun testStudentCanReadExpensesViaHttp() {
        val studentToken = getAuthToken("s1.arafat@messmate.com")
        val mess = messRepository.findAll().firstOrNull()
        val messId = mess?.id ?: "greenfield-hall"

        val req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/expenses?messId=$messId")))
            .header("Authorization", "Bearer $studentToken")
            .GET()
            .build()

        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, resp.statusCode(), "Student should receive 200 OK when reading expenses")
        val expenses: List<ExpenseDto> = objectMapper.readValue(resp.body())
        assertNotNull(expenses)
    }

    @Test
    fun testStudentCannotCreateExpenseViaHttp() {
        val studentToken = getAuthToken("s1.arafat@messmate.com")
        val createReq = CreateExpenseRequest(
            category = ExpenseCategory.MEAL_VARIABLE,
            title = "Unauthorized Student Expense",
            amount = BigDecimal("250.00"),
            expenseDate = LocalDate.now(),
            notes = "Attempt by student to write expense"
        )

        val req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/expenses")))
            .header("Authorization", "Bearer $studentToken")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(createReq)))
            .build()

        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(403, resp.statusCode(), "Student POST /api/expenses must return 403 Forbidden")
    }

    @Test
    fun testStudentCannotUpdateExpenseViaHttp() {
        val studentToken = getAuthToken("s1.arafat@messmate.com")
        val updateReq = CreateExpenseRequest(
            category = ExpenseCategory.MEAL_VARIABLE,
            title = "Unauthorized Update Attempt",
            amount = BigDecimal("999.00"),
            expenseDate = LocalDate.now()
        )

        val req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/expenses/dummy-expense-id")))
            .header("Authorization", "Bearer $studentToken")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(updateReq)))
            .build()

        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(403, resp.statusCode(), "Student PUT /api/expenses/{id} must return 403 Forbidden")
    }

    @Test
    fun testStudentCannotDeleteExpenseViaHttp() {
        val studentToken = getAuthToken("s1.arafat@messmate.com")
        val req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/expenses/dummy-expense-id")))
            .header("Authorization", "Bearer $studentToken")
            .DELETE()
            .build()

        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(403, resp.statusCode(), "Student DELETE /api/expenses/{id} must return 403 Forbidden")
    }

    @Test
    @Transactional
    fun testStudentServiceLevelWriteAuthorizationEnforced() {
        val expenseReq = CreateExpenseRequest(
            category = ExpenseCategory.MEAL_VARIABLE,
            title = "Direct Service Level Test",
            amount = BigDecimal("100.00"),
            expenseDate = LocalDate.now()
        )

        val createEx = assertThrows(ResponseStatusException::class.java) {
            expenseService.createExpense("s1.arafat@messmate.com", expenseReq)
        }
        assertEquals(HttpStatus.FORBIDDEN, createEx.statusCode)

        val updateEx = assertThrows(ResponseStatusException::class.java) {
            expenseService.updateExpense("non-existent-id", "s1.arafat@messmate.com", expenseReq)
        }
        assertEquals(HttpStatus.FORBIDDEN, updateEx.statusCode)

        val deleteEx = assertThrows(ResponseStatusException::class.java) {
            expenseService.deleteExpense("non-existent-id", "s1.arafat@messmate.com")
        }
        assertEquals(HttpStatus.FORBIDDEN, deleteEx.statusCode)
    }

    @Test
    fun testManagerCanCreateUpdateAndDeleteExpenseViaHttp() {
        val managerToken = getAuthToken("primary.manager@messmate.com")

        // 1. Create expense as Manager
        val createReq = CreateExpenseRequest(
            category = ExpenseCategory.MEAL_VARIABLE,
            title = "Manager Market Groceries",
            amount = BigDecimal("350.00"),
            expenseDate = LocalDate.now(),
            notes = "Weekly grocery bazaar"
        )
        val createHttpReq = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/expenses")))
            .header("Authorization", "Bearer $managerToken")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(createReq)))
            .build()

        val createResp = httpClient.send(createHttpReq, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, createResp.statusCode(), "Manager should be able to create expenses")
        val created = objectMapper.readValue<ExpenseDto>(createResp.body())
        assertEquals("Manager Market Groceries", created.title)

        // 2. Update expense as Manager
        val updateReq = CreateExpenseRequest(
            category = ExpenseCategory.MEAL_VARIABLE,
            title = "Manager Market Groceries Updated",
            amount = BigDecimal("400.00"),
            expenseDate = LocalDate.now(),
            notes = "Added extra spices"
        )
        val updateHttpReq = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/expenses/${created.id}")))
            .header("Authorization", "Bearer $managerToken")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(updateReq)))
            .build()

        val updateResp = httpClient.send(updateHttpReq, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, updateResp.statusCode(), "Manager should be able to update expenses")
        val updated = objectMapper.readValue<ExpenseDto>(updateResp.body())
        assertEquals(BigDecimal("400.00"), updated.amount)

        // 3. Delete expense as Manager
        val deleteHttpReq = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl("/api/expenses/${created.id}")))
            .header("Authorization", "Bearer $managerToken")
            .DELETE()
            .build()

        val deleteResp = httpClient.send(deleteHttpReq, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, deleteResp.statusCode(), "Manager should be able to delete expenses")
    }
}
