package com.example.messmate_backend.controller

import com.example.messmate_backend.dto.BookGuestMealRequest
import com.example.messmate_backend.dto.DailyMealStatusDto
import com.example.messmate_backend.dto.ToggleMealRequest
import com.example.messmate_backend.service.GuestMealDto
import com.example.messmate_backend.service.MealService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/api/meals")
class MealController(
    private val mealService: MealService
) {

    @PostMapping("/toggle")
    fun toggleMeal(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody request: ToggleMealRequest
    ): ResponseEntity<DailyMealStatusDto> {
        return ResponseEntity.ok(mealService.toggleMeal(userDetails.username, request))
    }

    @GetMapping("/my-status")
    fun getMyMealStatuses(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) startDate: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDate: LocalDate
    ): ResponseEntity<List<DailyMealStatusDto>> {
        return ResponseEntity.ok(mealService.getStudentMealStatuses(userDetails.username, startDate, endDate))
    }

    @PostMapping("/guest")
    fun bookGuestMeal(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody request: BookGuestMealRequest
    ): ResponseEntity<GuestMealDto> {
        return ResponseEntity.status(HttpStatus.CREATED).body(mealService.bookGuestMeal(userDetails.username, request))
    }

    @GetMapping("/guest")
    fun getMyGuestMeals(
        @AuthenticationPrincipal userDetails: UserDetails
    ): ResponseEntity<List<GuestMealDto>> {
        return ResponseEntity.ok(mealService.getMyGuestMeals(userDetails.username))
    }
}
