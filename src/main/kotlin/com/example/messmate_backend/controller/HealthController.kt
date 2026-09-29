package com.example.messmate_backend.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class HealthController {

    @GetMapping("/api/health", "/healthz", "/")
    fun healthCheck(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(
            mapOf(
                "status" to "UP",
                "service" to "messmate-backend",
                "timestamp" to Instant.now().toString()
            )
        )
    }
}
