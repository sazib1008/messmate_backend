package com.example.messmate_backend.controller

import com.example.messmate_backend.dto.StartNewCycleRequest
import com.example.messmate_backend.engine.CycleCalculationResult
import com.example.messmate_backend.service.ActiveCycleDto
import com.example.messmate_backend.service.CycleCalculationService
import com.example.messmate_backend.service.PastCycleSummaryDto
import com.example.messmate_backend.service.PauseDayRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/cycles")
class CycleController(
    private val calculationService: CycleCalculationService
) {

    @GetMapping("/active")
    fun getActiveCycle(@RequestParam messId: String): ResponseEntity<ActiveCycleDto> {
        return ResponseEntity.ok(calculationService.getActiveCycle(messId))
    }

    @PostMapping("/pause-day")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun pauseDay(@RequestBody request: PauseDayRequest): ResponseEntity<ActiveCycleDto> {
        return ResponseEntity.ok(calculationService.pauseDay(request))
    }

    @PostMapping("/new")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun startNewCycle(
        @RequestParam messId: String,
        @RequestBody request: StartNewCycleRequest,
        authentication: Authentication
    ): ResponseEntity<ActiveCycleDto> {
        return ResponseEntity.ok(calculationService.startNewCycle(messId, request, authentication.name))
    }

    @GetMapping("/history")
    fun getCycleHistory(@RequestParam messId: String): ResponseEntity<List<PastCycleSummaryDto>> {
        return ResponseEntity.ok(calculationService.getCycleHistory(messId))
    }
}

data class FinalizeCycleRequest(
    val messId: String,
    val confirmForfeitSurplus: Boolean = false
)

@RestController
@RequestMapping("/api/calculations")
class CalculationController(
    private val calculationService: CycleCalculationService
) {

    @GetMapping("/preview")
    fun previewCalculation(@RequestParam messId: String): ResponseEntity<CycleCalculationResult> {
        return ResponseEntity.ok(calculationService.calculateAndSave(messId, isFinal = false))
    }

    @PostMapping("/finalize")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun finalizeCalculation(@RequestBody request: FinalizeCycleRequest): ResponseEntity<CycleCalculationResult> {
        return ResponseEntity.ok(calculationService.calculateAndSave(request.messId, isFinal = true, confirmForfeitSurplus = request.confirmForfeitSurplus))
    }
}
