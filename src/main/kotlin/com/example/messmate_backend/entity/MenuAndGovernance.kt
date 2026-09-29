package com.example.messmate_backend.entity

import com.example.messmate_backend.model.enums.MealSession
import com.example.messmate_backend.model.enums.VoteDecision
import com.example.messmate_backend.model.enums.VoteStatus
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "menus")
data class Menu(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "\"messId\"", nullable = false)
    var messId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"messId\"", insertable = false, updatable = false)
    var mess: Mess? = null,

    @Column(name = "title", nullable = false)
    var title: String = "",

    @Column(name = "\"isActive\"", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "\"effectiveFrom\"", nullable = false)
    var effectiveFrom: LocalDate = LocalDate.now(),

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"updatedAt\"", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "menu_items")
data class MenuItem(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "\"menuId\"", nullable = false)
    var menuId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"menuId\"", insertable = false, updatable = false)
    var menu: Menu? = null,

    @Column(name = "\"dayOfWeek\"", nullable = false)
    var dayOfWeek: Int = 0, // 0 = Sunday, 1 = Monday, ... 6 = Saturday

    @Enumerated(EnumType.STRING)
    @Column(name = "session", nullable = false, columnDefinition = "varchar(50)")
    var session: MealSession = MealSession.LUNCH,

    @Column(name = "\"itemName\"", nullable = false)
    var itemName: String = "",

    @Column(name = "description")
    var description: String? = null,

    @Column(name = "category")
    var category: String? = null,

    @Column(name = "\"dietaryTags\"")
    @JdbcTypeCode(SqlTypes.ARRAY)
    var dietaryTags: List<String> = emptyList(),

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"updatedAt\"", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "manager_transfer_votes")
data class ManagerTransferVote(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "\"messId\"", nullable = false)
    var messId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"messId\"", insertable = false, updatable = false)
    var mess: Mess? = null,

    @Column(name = "\"initiatedById\"", nullable = false)
    var initiatedById: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"initiatedById\"", insertable = false, updatable = false)
    var initiatedBy: User? = null,

    @Column(name = "\"proposedUserId\"", nullable = false)
    var proposedUserId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"proposedUserId\"", insertable = false, updatable = false)
    var proposedUser: User? = null,

    @Column(name = "reason", nullable = false)
    var reason: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "varchar(50)")
    var status: VoteStatus = VoteStatus.PENDING,

    @Column(name = "\"expiresAt\"", nullable = false)
    var expiresAt: LocalDateTime = LocalDateTime.now().plusDays(3),

    @Column(name = "\"resolvedAt\"")
    var resolvedAt: LocalDateTime? = null,

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"updatedAt\"", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "transfer_vote_ballots")
data class TransferVoteBallot(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "\"voteId\"", nullable = false)
    var voteId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"voteId\"", insertable = false, updatable = false)
    var vote: ManagerTransferVote? = null,

    @Column(name = "\"managerId\"", nullable = false)
    var managerId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"managerId\"", insertable = false, updatable = false)
    var manager: User? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, columnDefinition = "varchar(50)")
    var decision: VoteDecision = VoteDecision.APPROVE,

    @Column(name = "\"votedAt\"", nullable = false)
    var votedAt: LocalDateTime = LocalDateTime.now()
)
