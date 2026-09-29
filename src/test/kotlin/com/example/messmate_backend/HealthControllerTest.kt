package com.example.messmate_backend

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthControllerTest {

    @LocalServerPort
    private var port: Int = 0

    private val httpClient = HttpClient.newHttpClient()
    private val objectMapper = ObjectMapper()

    @Test
    fun testHealthEndpointPermittedWithoutAuth() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/health"))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(200, response.statusCode())
        val body = objectMapper.readTree(response.body())
        assertEquals("UP", body.get("status").asText())
        assertEquals("messmate-backend", body.get("service").asText())
        assertNotNull(body.get("timestamp"))
    }

    @Test
    fun testHealthzEndpointPermittedWithoutAuth() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/healthz"))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(200, response.statusCode())
        val body = objectMapper.readTree(response.body())
        assertEquals("UP", body.get("status").asText())
    }

    @Test
    fun testRootEndpointPermittedWithoutAuth() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/"))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(200, response.statusCode())
        val body = objectMapper.readTree(response.body())
        assertEquals("UP", body.get("status").asText())
    }
}
