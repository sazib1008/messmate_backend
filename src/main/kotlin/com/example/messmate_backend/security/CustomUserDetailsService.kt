package com.example.messmate_backend.security

import com.example.messmate_backend.model.enums.MembershipStatus
import com.example.messmate_backend.repository.MessMembershipRepository
import com.example.messmate_backend.repository.UserRepository
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

@Service
class CustomUserDetailsService(
    private val userRepository: UserRepository,
    private val membershipRepository: MessMembershipRepository
) : UserDetailsService {

    override fun loadUserByUsername(email: String): UserDetails {
        val user = userRepository.findByEmail(email)
            .orElseThrow { UsernameNotFoundException("User not found with email: $email") }

        val membership = membershipRepository.findByUserIdAndStatus(user.id, MembershipStatus.ACTIVE)
        val role = membership.map { it.role.name }.orElse("STUDENT")

        val authorities = listOf(SimpleGrantedAuthority("ROLE_$role"))

        return User(
            user.email,
            user.passwordHash,
            authorities
        )
    }
}
