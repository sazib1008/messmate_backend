package com.example.messmate_backend.entity

import com.example.messmate_backend.model.enums.MembershipStatus
import com.example.messmate_backend.model.enums.UserRole
import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "messes")
data class Mess(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "name", nullable = false)
    var name: String = "",

    @Column(name = "code", nullable = false, unique = true)
    var code: String = "",

    @Column(name = "address")
    var address: String? = null,

    @Column(name = "\"createdById\"", nullable = false)
    var createdById: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"createdById\"", insertable = false, updatable = false)
    var creator: User? = null,

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"updatedAt\"", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "mess_memberships")
data class MessMembership(
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
    @Column(name = "role", nullable = false, columnDefinition = "varchar(50)")
    var role: UserRole = UserRole.STUDENT,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "varchar(50)")
    var status: MembershipStatus = MembershipStatus.ACTIVE,

    @Column(name = "\"joinDate\"", nullable = false)
    var joinDate: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"leaveDate\"")
    var leaveDate: LocalDateTime? = null,

    @Column(name = "notes")
    var notes: String? = null,

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"updatedAt\"", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

typealias Meal = Mess
typealias MealMembership = MessMembership

