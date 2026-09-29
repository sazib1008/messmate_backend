package com.example.messmate_backend.service

import com.example.messmate_backend.dto.*
import com.example.messmate_backend.entity.User
import com.example.messmate_backend.model.enums.JoinRequestStatus
import com.example.messmate_backend.model.enums.MembershipStatus
import com.example.messmate_backend.repository.MealJoinRequestRepository
import com.example.messmate_backend.repository.MessMembershipRepository
import com.example.messmate_backend.repository.UserRepository
import com.example.messmate_backend.security.JwtTokenProvider
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val membershipRepository: MessMembershipRepository,
    private val joinRequestRepository: MealJoinRequestRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenProvider: JwtTokenProvider
) {

    private fun mapUserDto(user: User): UserDto {
        return UserDto(
            id = user.id,
            email = user.email,
            fullName = user.fullName,
            phone = user.phone,
            avatarUrl = user.avatarUrl,
            studentId = user.studentId,
            department = user.department,
            university = user.university,
            roomNumber = user.roomNumber,
            isProfileComplete = user.isProfileComplete || !user.roomNumber.isNullOrBlank()
        )
    }

    private fun findPendingJoinRequest(userId: String): PendingJoinRequestDto? {
        return joinRequestRepository.findFirstByUserIdAndStatus(userId, JoinRequestStatus.PENDING)
            .map {
                PendingJoinRequestDto(
                    id = it.id,
                    mealId = it.messId,
                    mealName = it.mess?.name ?: "Meal Group",
                    mealCode = it.mess?.code ?: "",
                    ownerName = it.mess?.creator?.fullName ?: "Meal Owner",
                    status = it.status,
                    requestNotes = it.requestNotes,
                    createdAt = it.createdAt
                )
            }.orElse(null)
    }

    @Transactional
    fun register(request: RegisterRequest): AuthResponse {
        if (userRepository.existsByEmail(request.email)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Email already in use")
        }

        val hasRoom = !request.roomNumber.isNullOrBlank()
        val user = User(
            email = request.email.trim().lowercase(),
            passwordHash = passwordEncoder.encode(request.password) ?: "",
            fullName = request.fullName.trim(),
            phone = request.phone?.trim(),
            studentId = request.studentId?.trim(),
            department = request.department?.trim(),
            university = request.university?.trim(),
            roomNumber = request.roomNumber?.trim(),
            isProfileComplete = hasRoom
        )
        val savedUser = userRepository.save(user)

        val token = tokenProvider.generateToken(savedUser.email, savedUser.id, "MEMBER")

        return AuthResponse(
            token = token,
            user = mapUserDto(savedUser),
            activeMembership = null,
            pendingJoinRequest = null
        )
    }

    @Transactional
    fun login(request: LoginRequest): AuthResponse {
        val email = request.email.trim().lowercase()
        val user = userRepository.findByEmail(email)
            .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password") }

        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password")
        }

        // Update lastActiveAt
        user.lastActiveAt = LocalDateTime.now()
        userRepository.save(user)

        val membership = membershipRepository.findByUserIdAndStatus(user.id, MembershipStatus.ACTIVE)
        val role = membership.map { it.role.name }.orElse("MEMBER")

        val token = tokenProvider.generateToken(user.email, user.id, role)

        val membershipDto = membership.map {
            MembershipDto(
                id = it.id,
                mealId = it.messId,
                mealName = it.mess?.name ?: "Unknown Meal",
                mealCode = it.mess?.code ?: "",
                role = it.role,
                status = it.status,
                joinDate = it.joinDate
            )
        }.orElse(null)

        val pendingRequest = if (membershipDto == null) findPendingJoinRequest(user.id) else null

        return AuthResponse(
            token = token,
            user = mapUserDto(user),
            activeMembership = membershipDto,
            pendingJoinRequest = pendingRequest
        )
    }

    @Transactional(readOnly = true)
    fun getCurrentUser(email: String): AuthResponse {
        val user = userRepository.findByEmail(email)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        val membership = membershipRepository.findByUserIdAndStatus(user.id, MembershipStatus.ACTIVE)
        val role = membership.map { it.role.name }.orElse("MEMBER")

        val token = tokenProvider.generateToken(user.email, user.id, role)

        val membershipDto = membership.map {
            MembershipDto(
                id = it.id,
                mealId = it.messId,
                mealName = it.mess?.name ?: "Unknown Meal",
                mealCode = it.mess?.code ?: "",
                role = it.role,
                status = it.status,
                joinDate = it.joinDate
            )
        }.orElse(null)

        val pendingRequest = if (membershipDto == null) findPendingJoinRequest(user.id) else null

        return AuthResponse(
            token = token,
            user = mapUserDto(user),
            activeMembership = membershipDto,
            pendingJoinRequest = pendingRequest
        )
    }

    @Transactional
    fun updateProfile(email: String, request: UpdateProfileRequest): UserDto {
        val user = userRepository.findByEmail(email)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        if (request.roomNumber.trim().isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Room number is required to complete profile setup")
        }

        if (!request.fullName.isNullOrBlank()) {
            user.fullName = request.fullName.trim()
        }
        if (request.phone != null) {
            user.phone = request.phone.trim()
        }
        if (request.studentId != null) {
            user.studentId = request.studentId.trim()
        }
        if (request.department != null) {
            user.department = request.department.trim()
        }
        if (request.university != null) {
            user.university = request.university.trim()
        }
        user.roomNumber = request.roomNumber.trim()
        user.isProfileComplete = true
        user.updatedAt = LocalDateTime.now()

        val saved = userRepository.save(user)
        return mapUserDto(saved)
    }
}
