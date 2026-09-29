package com.example.messmate_backend.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun authenticationManager(authConfig: AuthenticationConfiguration): AuthenticationManager =
        authConfig.authenticationManager

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { it.configurationSource(corsConfigurationSource()) }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers("/error").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/menus/**").authenticated()
                    .requestMatchers("/api/menus/**").hasAnyRole("OWNER", "PRIMARY_MANAGER", "MANAGER")
                    .requestMatchers(HttpMethod.GET, "/api/expenses/**").authenticated()
                    .requestMatchers("/api/expenses/**").hasAnyRole("OWNER", "PRIMARY_MANAGER", "MANAGER")
                    .requestMatchers(HttpMethod.PUT, "/api/messes/*/config", "/api/meals/*/config").hasAnyRole("OWNER", "PRIMARY_MANAGER", "MANAGER")
                    .requestMatchers("/api/cycles/pause-day").hasAnyRole("OWNER", "PRIMARY_MANAGER", "MANAGER")
                    .requestMatchers("/api/cycles/new").hasAnyRole("OWNER", "PRIMARY_MANAGER", "MANAGER")
                    .requestMatchers(HttpMethod.GET, "/api/calculations/preview").authenticated()
                    .requestMatchers("/api/calculations/**").hasAnyRole("OWNER", "PRIMARY_MANAGER", "MANAGER")
                    .requestMatchers("/api/chef/**").hasAnyRole("CHEF", "OWNER", "PRIMARY_MANAGER", "MANAGER")
                    .requestMatchers(HttpMethod.POST, "/api/deposits").authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/deposits", "/api/deposits/**").authenticated()
                    .requestMatchers("/api/deposits/**").hasAnyRole("OWNER", "PRIMARY_MANAGER", "MANAGER")
                    .anyRequest().authenticated()
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.allowedOriginPatterns = listOf("*")
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        configuration.allowedHeaders = listOf("*")
        configuration.allowCredentials = true

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}
