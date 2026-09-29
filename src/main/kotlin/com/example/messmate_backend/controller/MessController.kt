package com.example.messmate_backend.controller

import com.example.messmate_backend.dto.*
import com.example.messmate_backend.model.enums.UserRole
import com.example.messmate_backend.service.MessService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping(value = ["/api/meals", "/api/messes"])
class MessController(
    private val messService: MessService
) {

    @PostMapping
    fun createMeal(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody request: CreateMealRequest
    ): ResponseEntity<MealDto> {
        return ResponseEntity.ok(messService.createMess(userDetails.username, request))
    }

    @PostMapping("/verify-code")
    fun verifyMealCode(
        @RequestBody request: VerifyMealCodeRequest
    ): ResponseEntity<MealCodeVerificationResponse> {
        return ResponseEntity.ok(messService.verifyMealCode(request.code))
    }

    @PostMapping("/join-request")
    fun submitJoinRequest(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody request: SubmitJoinRequest
    ): ResponseEntity<MealJoinRequestDto> {
        return ResponseEntity.status(HttpStatus.CREATED).body(messService.submitJoinRequest(userDetails.username, request))
    }

    @GetMapping("/my-join-request")
    fun getMyPendingJoinRequest(
        @AuthenticationPrincipal userDetails: UserDetails
    ): ResponseEntity<Any> {
        val req = messService.getMyPendingJoinRequest(userDetails.username)
        return if (req != null) ResponseEntity.ok(req) else ResponseEntity.ok(emptyMap<String, Any>())
    }

    @DeleteMapping("/join-requests/{requestId}")
    fun cancelMyJoinRequest(
        @AuthenticationPrincipal userDetails: UserDetails,
        @PathVariable requestId: String
    ): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(messService.cancelMyJoinRequest(userDetails.username, requestId))
    }

    @GetMapping("/{id}/join-requests")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun listJoinRequests(
        @PathVariable id: String,
        authentication: Authentication
    ): ResponseEntity<List<MealJoinRequestDto>> {
        return ResponseEntity.ok(messService.listJoinRequests(id, authentication.name))
    }

    @PostMapping("/{id}/join-requests/{requestId}/review")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun reviewJoinRequest(
        @PathVariable id: String,
        @PathVariable requestId: String,
        @RequestBody review: ReviewJoinRequest,
        authentication: Authentication
    ): ResponseEntity<MealJoinRequestDto> {
        return ResponseEntity.ok(messService.reviewJoinRequest(id, requestId, review, authentication.name))
    }

    @PostMapping("/join")
    fun joinMess(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody request: JoinMessRequest
    ): ResponseEntity<MembershipDto> {
        return ResponseEntity.ok(messService.joinMess(userDetails.username, request))
    }

    @GetMapping("/{id}")
    fun getMealDetails(@PathVariable id: String): ResponseEntity<MealDto> {
        return ResponseEntity.ok(messService.getMealDetails(id))
    }

    @GetMapping("/{id}/members")
    fun getMembers(@PathVariable id: String): ResponseEntity<List<Map<String, Any?>>> {
        return ResponseEntity.ok(messService.getMembers(id))
    }

    @GetMapping("/{id}/config")
    fun getDiningConfig(@PathVariable id: String): ResponseEntity<DiningConfigDto> {
        return ResponseEntity.ok(messService.getDiningConfig(id))
    }

    @PutMapping("/{id}/config")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun updateDiningConfig(
        @PathVariable id: String,
        @RequestBody request: DiningConfigDto
    ): ResponseEntity<DiningConfigDto> {
        return ResponseEntity.ok(
            messService.updateDiningConfig(
                id,
                request.defaultCarryForward,
                request.sessions
            )
        )
    }

    data class UpdateRoleRequest(
        val role: UserRole
    )

    @PutMapping("/{id}/members/{membershipId}/role")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun updateMemberRole(
        @PathVariable id: String,
        @PathVariable membershipId: String,
        @RequestBody request: UpdateRoleRequest,
        authentication: Authentication
    ): ResponseEntity<Map<String, Any?>> {
        return ResponseEntity.ok(
            messService.updateMemberRole(id, membershipId, request.role, authentication.name)
        )
    }

    @PutMapping("/{id}/members/{membershipId}/room")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun updateMemberRoom(
        @PathVariable id: String,
        @PathVariable membershipId: String,
        @RequestBody request: UpdateMemberRoomRequest,
        authentication: Authentication
    ): ResponseEntity<Map<String, Any?>> {
        return ResponseEntity.ok(
            messService.updateMemberRoom(id, membershipId, request.roomNumber, authentication.name)
        )
    }

    @DeleteMapping("/{id}/members/{membershipId}")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun removeMember(
        @PathVariable id: String,
        @PathVariable membershipId: String,
        authentication: Authentication
    ): ResponseEntity<Map<String, Any?>> {
        return ResponseEntity.ok(
            messService.removeMember(id, membershipId, authentication.name)
        )
    }
}
