package com.example.messmate_backend.controller

import com.example.messmate_backend.dto.AuthResponse
import com.example.messmate_backend.dto.LoginRequest
import com.example.messmate_backend.dto.RegisterRequest
import com.example.messmate_backend.service.AuthService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService
) {

    @PostMapping("/register")
    fun register(@RequestBody request: RegisterRequest): ResponseEntity<AuthResponse> {
        return ResponseEntity.ok(authService.register(request))
    }

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): ResponseEntity<AuthResponse> {
        return ResponseEntity.ok(authService.login(request))
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal userDetails: UserDetails): ResponseEntity<AuthResponse> {
        return ResponseEntity.ok(authService.getCurrentUser(userDetails.username))
    }

    @PutMapping("/profile")
    fun updateProfile(
        @AuthenticationPrincipal userDetails: UserDetails,
        @RequestBody request: com.example.messmate_backend.dto.UpdateProfileRequest
    ): ResponseEntity<com.example.messmate_backend.dto.UserDto> {
        return ResponseEntity.ok(authService.updateProfile(userDetails.username, request))
    }
}
