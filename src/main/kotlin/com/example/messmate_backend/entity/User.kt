package com.example.messmate_backend.entity

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "users")
data class User(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "email", nullable = false, unique = true)
    var email: String = "",

    @Column(name = "\"passwordHash\"", nullable = false)
    var passwordHash: String = "",

    @Column(name = "\"fullName\"", nullable = false)
    var fullName: String = "",

    @Column(name = "phone")
    var phone: String? = null,

    @Column(name = "\"avatarUrl\"")
    var avatarUrl: String? = null,

    @Column(name = "\"studentId\"")
    var studentId: String? = null,

    @Column(name = "department")
    var department: String? = null,

    @Column(name = "university")
    var university: String? = null,

    @Column(name = "\"roomNumber\"")
    var roomNumber: String? = null,

    @Column(name = "\"isProfileComplete\"", nullable = false)
    var isProfileComplete: Boolean = false,

    @Column(name = "\"lastActiveAt\"", nullable = false)
    var lastActiveAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"updatedAt\"", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
