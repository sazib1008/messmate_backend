package com.example.messmate_backend.entity

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(
    name = "device_tokens",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_user_device_token", columnNames = ["\"userId\"", "token"])
    ]
)
data class DeviceToken(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "\"userId\"", nullable = false)
    var userId: String = "",

    @Column(name = "token", nullable = false)
    var token: String = "",

    @Column(name = "\"deviceType\"", nullable = false)
    var deviceType: String = "ANDROID",

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"updatedAt\"", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
) {
    val fcmToken: String get() = token
    val platform: String get() = deviceType
}
