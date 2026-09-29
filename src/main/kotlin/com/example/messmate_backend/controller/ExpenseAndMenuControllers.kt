package com.example.messmate_backend.controller

import com.example.messmate_backend.dto.CreateExpenseRequest
import com.example.messmate_backend.dto.ExpenseDto
import com.example.messmate_backend.dto.MenuItemDto
import com.example.messmate_backend.dto.WeeklyMenuDto
import com.example.messmate_backend.model.enums.MealSession
import com.example.messmate_backend.service.ExpenseService
import com.example.messmate_backend.service.MenuService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/expenses")
class ExpenseController(
    private val expenseService: ExpenseService
) {

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun createExpense(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody request: CreateExpenseRequest
    ): ResponseEntity<ExpenseDto> {
        return ResponseEntity.ok(expenseService.createExpense(userDetails.username, request))
    }

    @GetMapping
    fun listExpenses(@RequestParam messId: String): ResponseEntity<List<ExpenseDto>> {
        return ResponseEntity.ok(expenseService.listExpenses(messId))
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun updateExpense(
        @PathVariable id: String,
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody request: CreateExpenseRequest
    ): ResponseEntity<ExpenseDto> {
        return ResponseEntity.ok(expenseService.updateExpense(id, userDetails.username, request))
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun patchExpense(
        @PathVariable id: String,
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody request: CreateExpenseRequest
    ): ResponseEntity<ExpenseDto> {
        return ResponseEntity.ok(expenseService.updateExpense(id, userDetails.username, request))
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun deleteExpense(
        @PathVariable id: String,
        @AuthenticationPrincipal userDetails: UserDetails
    ): ResponseEntity<Map<String, String>> {
        expenseService.deleteExpense(id, userDetails.username)
        return ResponseEntity.ok(mapOf("message" to "Expense deleted successfully"))
    }
}

@RestController
@RequestMapping("/api/menus")
class MenuController(
    private val menuService: MenuService
) {

    @GetMapping("/weekly")
    fun getWeeklyMenu(@RequestParam messId: String): ResponseEntity<WeeklyMenuDto> {
        return ResponseEntity.ok(menuService.getActiveWeeklyMenu(messId))
    }

    @GetMapping("/today")
    fun getTodayMenu(@RequestParam messId: String): ResponseEntity<List<MenuItemDto>> {
        return ResponseEntity.ok(menuService.getTodayMenu(messId))
    }

    @PostMapping("/items")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun createMenuItem(@RequestBody request: CreateMenuItemRequest): ResponseEntity<MenuItemDto> {
        val created = menuService.createMenuItem(
            messId = request.messId,
            dayOfWeek = request.dayOfWeek,
            session = request.session,
            itemName = request.itemName,
            description = request.description,
            category = request.category,
            dietaryTags = request.dietaryTags
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(created)
    }

    @DeleteMapping("/items/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'PRIMARY_MANAGER', 'MANAGER')")
    fun deleteMenuItem(@PathVariable id: String): ResponseEntity<Map<String, String>> {
        menuService.deleteMenuItem(id)
        return ResponseEntity.ok(mapOf("message" to "Menu item deleted successfully"))
    }
}

data class CreateMenuItemRequest(
    val messId: String,
    val dayOfWeek: Int,
    val session: MealSession,
    val itemName: String,
    val description: String? = null,
    val category: String? = null,
    val dietaryTags: List<String> = emptyList()
)
