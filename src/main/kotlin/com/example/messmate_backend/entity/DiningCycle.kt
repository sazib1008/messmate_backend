package com.example.messmate_backend.entity

import com.example.messmate_backend.model.enums.CycleStatus
import com.example.messmate_backend.model.enums.MealSession
import com.example.messmate_backend.model.enums.MealStatus
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "dining_cycles")
data class DiningCycle(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "\"messId\"", nullable = false)
    var messId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"messId\"", insertable = false, updatable = false)
    var mess: Mess? = null,

    @Column(name = "\"cycleNumber\"", nullable = false)
    var cycleNumber: Int = 1,

    @Column(name = "\"startDate\"", nullable = false)
    var startDate: LocalDate = LocalDate.now(),

    @Column(name = "\"targetActiveDays\"", nullable = false)
    var targetActiveDays: Int = 30,

    @Column(name = "\"countedActiveDays\"", nullable = false)
    var countedActiveDays: Int = 0,

    @Column(name = "\"scheduledEndDate\"", nullable = false)
    var scheduledEndDate: LocalDate = LocalDate.now().plusDays(30),

    @Column(name = "\"actualEndDate\"")
    var actualEndDate: LocalDate? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "varchar(50)")
    var status: CycleStatus = CycleStatus.ACTIVE,

    @Column(name = "notes")
    var notes: String? = null,

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"updatedAt\"", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

typealias MealCycle = DiningCycle

@Entity
@Table(name = "cycle_paused_days")
data class CyclePausedDay(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "\"cycleId\"", nullable = false)
    var cycleId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"cycleId\"", insertable = false, updatable = false)
    var cycle: DiningCycle? = null,

    @Column(name = "\"pausedDate\"", nullable = false)
    var pausedDate: LocalDate = LocalDate.now(),

    @Enumerated(EnumType.STRING)
    @Column(name = "session", columnDefinition = "varchar(50)")
    var session: MealSession? = null,

    @Column(name = "reason", nullable = false)
    var reason: String = "",

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "daily_meal_statuses")
data class DailyMealStatus(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "\"cycleId\"", nullable = false)
    var cycleId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"cycleId\"", insertable = false, updatable = false)
    var cycle: DiningCycle? = null,

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

    @Column(name = "date", nullable = false)
    var date: LocalDate = LocalDate.now(),

    @Enumerated(EnumType.STRING)
    @Column(name = "session", nullable = false, columnDefinition = "varchar(50)")
    var session: MealSession = MealSession.LUNCH,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "varchar(50)")
    var status: MealStatus = MealStatus.ON,

    @Column(name = "\"unitValue\"", nullable = false, precision = 3, scale = 2)
    var unitValue: BigDecimal = BigDecimal("1.00"),

    @Column(name = "\"isAutoCarried\"", nullable = false)
    var isAutoCarried: Boolean = false,

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"updatedAt\"", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "guest_meals")
data class GuestMeal(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "\"cycleId\"", nullable = false)
    var cycleId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"cycleId\"", insertable = false, updatable = false)
    var cycle: DiningCycle? = null,

    @Column(name = "\"messId\"", nullable = false)
    var messId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"messId\"", insertable = false, updatable = false)
    var mess: Mess? = null,

    @Column(name = "\"hostUserId\"", nullable = false)
    var hostUserId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"hostUserId\"", insertable = false, updatable = false)
    var hostUser: User? = null,

    @Column(name = "date", nullable = false)
    var date: LocalDate = LocalDate.now(),

    @Enumerated(EnumType.STRING)
    @Column(name = "session", nullable = false, columnDefinition = "varchar(50)")
    var session: MealSession = MealSession.DINNER,

    @Column(name = "\"guestCount\"", nullable = false)
    var guestCount: Int = 1,

    @Column(name = "\"mealUnits\"", nullable = false, precision = 4, scale = 2)
    var mealUnits: BigDecimal = BigDecimal("1.00"),

    @Column(name = "\"provisionalRate\"", precision = 10, scale = 2)
    var provisionalRate: BigDecimal? = null,

    @Column(name = "\"finalRate\"", precision = 10, scale = 2)
    var finalRate: BigDecimal? = null,

    @Column(name = "\"totalCost\"", precision = 10, scale = 2)
    var totalCost: BigDecimal? = null,

    @Column(name = "\"guestName\"")
    var guestName: String? = null,

    @Column(name = "notes")
    var notes: String? = null,

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"updatedAt\"", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
