package com.example.messmate_backend.controller

import com.example.messmate_backend.dto.*
import com.example.messmate_backend.model.enums.DepositStatus
import com.example.messmate_backend.service.*
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.time.ZoneId

@RestController
@RequestMapping("/api")
class ManagerAndChefControllers(
    private val depositService: DepositService,
    private val chefService: ChefService,
    private val mealExportService: MealExportService,
    private val governanceService: GovernanceService
) {

    // ========================== DEPOSIT ENDPOINTS ==========================

    @PostMapping("/deposits")
    fun submitDeposit(
        @Valid @RequestBody req: CreateDepositRequest,
        authentication: Authentication
    ): ResponseEntity<DepositResponse> {
        val userId = authentication.name
        val response = depositService.submitDeposit(userId, req)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/deposits")
    fun listDeposits(
        @RequestParam(required = false) messId: String?,
        @RequestParam(required = false) userId: String?,
        @RequestParam(required = false) status: DepositStatus?,
        authentication: Authentication
    ): ResponseEntity<List<DepositResponse>> {
        val callerUserId = authentication.name
        val deposits = depositService.listDeposits(messId, userId, status, callerUserId)
        return ResponseEntity.ok(deposits)
    }

    @PostMapping("/deposits/{id}/review")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun reviewDeposit(
        @PathVariable id: String,
        @Valid @RequestBody req: ReviewDepositRequest,
        authentication: Authentication
    ): ResponseEntity<DepositResponse> {
        val reviewerUserId = authentication.name
        val response = depositService.reviewDeposit(id, reviewerUserId, req)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/deposits/{id}/approve")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun approveDeposit(
        @PathVariable id: String,
        authentication: Authentication
    ): ResponseEntity<DepositResponse> {
        val reviewerUserId = authentication.name
        val response = depositService.reviewDeposit(id, reviewerUserId, ReviewDepositRequest(approved = true))
        return ResponseEntity.ok(response)
    }

    @PostMapping("/deposits/{id}/reject")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun rejectDeposit(
        @PathVariable id: String,
        @RequestBody(required = false) req: ReviewDepositRequest?,
        authentication: Authentication
    ): ResponseEntity<DepositResponse> {
        val reviewerUserId = authentication.name
        val response = depositService.reviewDeposit(
            id,
            reviewerUserId,
            req ?: ReviewDepositRequest(approved = false, rejectionReason = "Rejected by manager")
        )
        return ResponseEntity.ok(response)
    }

    @GetMapping("/deposits/{id}")
    fun getDepositById(
        @PathVariable id: String,
        authentication: Authentication
    ): ResponseEntity<DepositResponse> {
        val callerUserId = authentication.name
        return ResponseEntity.ok(depositService.getDepositById(id, callerUserId))
    }

    @PutMapping("/deposits/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun updateDeposit(
        @PathVariable id: String,
        @Valid @RequestBody req: UpdateDepositRequest,
        authentication: Authentication
    ): ResponseEntity<DepositResponse> {
        val managerUserId = authentication.name
        val response = depositService.updateDeposit(id, managerUserId, req)
        return ResponseEntity.ok(response)
    }

    @PatchMapping("/deposits/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun patchDeposit(
        @PathVariable id: String,
        @Valid @RequestBody req: UpdateDepositRequest,
        authentication: Authentication
    ): ResponseEntity<DepositResponse> {
        val managerUserId = authentication.name
        val response = depositService.updateDeposit(id, managerUserId, req)
        return ResponseEntity.ok(response)
    }

    @DeleteMapping("/deposits/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun deleteDeposit(
        @PathVariable id: String,
        authentication: Authentication
    ): ResponseEntity<Map<String, String>> {
        val managerUserId = authentication.name
        depositService.deleteDeposit(id, managerUserId)
        return ResponseEntity.ok(mapOf("message" to "Deposit deleted successfully"))
    }

    // ========================== CHEF HEADCOUNT ENDPOINTS ==========================

    @GetMapping("/chef/headcount")
    @PreAuthorize("hasAnyRole('CHEF', 'OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun getChefHeadcount(
        @RequestParam messId: String,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?
    ): ResponseEntity<ChefDailyHeadcountResponse> {
        val targetDate = date ?: LocalDate.now(ZoneId.of("Asia/Dhaka"))
        val response = chefService.getDailyHeadcount(messId, targetDate)
        return ResponseEntity.ok(response)
    }

    // ========================== NOTICE BOARD MEAL EXPORT ==========================

    @GetMapping("/meals/export")
    fun exportMealStatus(
        @RequestParam messId: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) startDate: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDate: LocalDate,
        @RequestParam(defaultValue = "json") format: String
    ): ResponseEntity<Any> {
        return if (format.lowercase() == "csv") {
            val csvData = mealExportService.exportToCsv(messId, startDate, endDate)
            ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"messmate_meal_roster_${startDate}_${endDate}.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csvData)
        } else {
            val jsonData = mealExportService.getExportRows(messId, startDate, endDate)
            ResponseEntity.ok(jsonData)
        }
    }

    // ========================== GOVERNANCE / MANAGER TRANSFER ==========================

    @PostMapping("/governance/transfer-manager/initiate")
    fun initiateTransferVote(
        @Valid @RequestBody req: InitiateTransferVoteRequest,
        authentication: Authentication
    ): ResponseEntity<TransferVoteResponse> {
        val callerUserId = authentication.name
        val response = governanceService.initiateTransferVote(callerUserId, req)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @PostMapping("/governance/transfer-manager/{id}/vote")
    fun castVote(
        @PathVariable id: String,
        @Valid @RequestBody req: CastVoteRequest,
        authentication: Authentication
    ): ResponseEntity<TransferVoteResponse> {
        val voterUserId = authentication.name
        val response = governanceService.castVote(id, voterUserId, req)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/governance/transfer-manager/{id}")
    fun getVoteStatus(
        @PathVariable id: String
    ): ResponseEntity<TransferVoteResponse> {
        val response = governanceService.getVoteStatus(id)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/governance/transfer-manager/active")
    fun getActiveVote(
        @RequestParam messId: String
    ): ResponseEntity<TransferVoteResponse> {
        val response = governanceService.getActiveVote(messId)
        return if (response != null) ResponseEntity.ok(response) else ResponseEntity.notFound().build()
    }

    @PostMapping("/governance/transfer-manager/{id}/execute")
    fun executeTransferVote(
        @PathVariable id: String,
        authentication: Authentication
    ): ResponseEntity<TransferVoteResponse> {
        val executorUserId = authentication.name
        val response = governanceService.executeTransferVote(id, executorUserId)
        return ResponseEntity.ok(response)
    }
}
