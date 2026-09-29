package com.example.messmate_backend.entity

import com.example.messmate_backend.model.enums.JoinRequestStatus
import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "meal_join_requests")
data class MealJoinRequest(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "\"messId\"", nullable = false)
    var messId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"messId\"", insertable = false, updatable = false)
    var mess: Mess? = null,

    @Column(name = "\"userId\"", nullable = false)
    var userId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"userId\"", insertable = false, updatable = false)
    var user: User? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "varchar(50)")
    var status: JoinRequestStatus = JoinRequestStatus.PENDING,

    @Column(name = "\"requestNotes\"")
    var requestNotes: String? = null,

    @Column(name = "\"reviewedById\"")
    var reviewedById: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"reviewedById\"", insertable = false, updatable = false)
    var reviewedBy: User? = null,

    @Column(name = "\"reviewedAt\"")
    var reviewedAt: LocalDateTime? = null,

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"updatedAt\"", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
