package com.example.messmate_backend.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "cycle_configurations")
data class CycleConfiguration(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "\"cycleId\"", nullable = false, unique = true)
    var cycleId: String = "",

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"cycleId\"", insertable = false, updatable = false)
    var cycle: DiningCycle? = null,

    @Column(name = "\"messId\"", nullable = false)
    var messId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"messId\"", insertable = false, updatable = false)
    var mess: Mess? = null,

    @Column(name = "currency", nullable = false)
    var currency: String = "BDT",

    @Column(name = "\"defaultCarryForward\"", nullable = false)
    var defaultCarryForward: Boolean = true,

    @Column(name = "\"breakfastEnabled\"", nullable = false)
    var breakfastEnabled: Boolean = true,

    @Column(name = "\"lunchEnabled\"", nullable = false)
    var lunchEnabled: Boolean = true,

    @Column(name = "\"dinnerEnabled\"", nullable = false)
    var dinnerEnabled: Boolean = true,

    @Column(name = "\"breakfastCutoff\"", nullable = false)
    var breakfastCutoff: String = "07:00",

    @Column(name = "\"lunchCutoff\"", nullable = false)
    var lunchCutoff: String = "12:00",

    @Column(name = "\"dinnerCutoff\"", nullable = false)
    var dinnerCutoff: String = "19:00",

    @Column(name = "\"breakfastMultiplier\"", nullable = false, precision = 3, scale = 2)
    var breakfastMultiplier: BigDecimal = BigDecimal("0.50"),

    @Column(name = "\"lunchMultiplier\"", nullable = false, precision = 3, scale = 2)
    var lunchMultiplier: BigDecimal = BigDecimal("1.00"),

    @Column(name = "\"dinnerMultiplier\"", nullable = false, precision = 3, scale = 2)
    var dinnerMultiplier: BigDecimal = BigDecimal("1.00"),

    @Column(name = "\"combinedSessionRule\"", nullable = false)
    var combinedSessionRule: String = "SEPARATE", // SEPARATE, LUNCH_DINNER_COMBINED, FULL_DAY

    @Column(name = "\"targetActiveDays\"", nullable = false)
    var targetActiveDays: Int = 30,

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"updatedAt\"", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
