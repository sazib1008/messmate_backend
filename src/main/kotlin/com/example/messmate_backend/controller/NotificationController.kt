package com.example.messmate_backend.controller

import com.example.messmate_backend.service.DeviceTokenRegistrationDto
import com.example.messmate_backend.service.NotificationService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/notifications")
class NotificationController(
    private val notificationService: NotificationService
) {

    @PostMapping("/device-token")
    fun registerDeviceToken(
        authentication: Authentication,
        @RequestBody request: DeviceTokenRegistrationDto
    ): ResponseEntity<Map<String, String>> {
        notificationService.registerDeviceToken(authentication.name, request)
        return ResponseEntity.ok(mapOf("status" to "success", "message" to "Device token registered"))
    }

    @DeleteMapping("/device-token")
    fun unregisterDeviceToken(
        @RequestParam token: String
    ): ResponseEntity<Map<String, String>> {
        notificationService.unregisterDeviceToken(token)
        return ResponseEntity.ok(mapOf("status" to "success", "message" to "Device token unregistered"))
    }
}
