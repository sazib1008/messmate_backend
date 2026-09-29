package com.example.messmate_backend.entity

import com.example.messmate_backend.model.enums.MealSession
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "dining_configurations")
data class DiningConfiguration(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "\"messId\"", nullable = false, unique = true)
    var messId: String = "",

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"messId\"", insertable = false, updatable = false)
    var mess: Mess? = null,

    @Column(name = "\"defaultCarryForward\"", nullable = false)
    var defaultCarryForward: Boolean = true,

    @Column(name = "\"defaultBreakfastOn\"", nullable = false)
    var defaultBreakfastOn: Boolean = true,

    @Column(name = "\"defaultLunchOn\"", nullable = false)
    var defaultLunchOn: Boolean = true,

    @Column(name = "\"defaultDinnerOn\"", nullable = false)
    var defaultDinnerOn: Boolean = true,

    @Column(name = "currency", nullable = false)
    var currency: String = "BDT",

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"updatedAt\"", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "meal_session_configs")
data class MealSessionConfig(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "\"diningConfigId\"", nullable = false)
    var diningConfigId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"diningConfigId\"", insertable = false, updatable = false)
    var diningConfig: DiningConfiguration? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "session", nullable = false)
    var session: MealSession = MealSession.LUNCH,

    @Column(name = "\"unitValue\"", nullable = false, precision = 3, scale = 2)
    var unitValue: BigDecimal = BigDecimal("1.00"),

    @Column(name = "\"cutoffTime\"", nullable = false)
    var cutoffTime: String = "12:00",

    @Column(name = "\"servingStartTime\"", nullable = true)
    var servingStartTime: String? = null,

    @Column(name = "\"servingEndTime\"", nullable = true)
    var servingEndTime: String? = null,

    @Column(name = "\"isEnabled\"", nullable = false)
    var isEnabled: Boolean = true,

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"updatedAt\"", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
