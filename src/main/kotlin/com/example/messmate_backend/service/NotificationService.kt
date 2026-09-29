package com.example.messmate_backend.service

import com.example.messmate_backend.entity.DeviceToken
import com.example.messmate_backend.repository.DeviceTokenRepository
import com.example.messmate_backend.repository.UserRepository
import com.google.firebase.messaging.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class DeviceTokenRegistrationDto(
    val token: String,
    val platform: String? = "ANDROID",
    val deviceType: String? = null
)

@Service
class NotificationService(
    private val deviceTokenRepository: DeviceTokenRepository,
    private val userRepository: UserRepository,
    private val firebaseMessaging: FirebaseMessaging?
) {
    private val logger = LoggerFactory.getLogger(NotificationService::class.java)

    private fun resolveUserId(identifier: String): String {
        return userRepository.findByEmail(identifier)
            .map { it.id }
            .orElse(identifier)
    }

    @Transactional
    fun registerDeviceToken(identifier: String, dto: DeviceTokenRegistrationDto) {
        val actualUserId = resolveUserId(identifier)
        val platform = dto.platform ?: dto.deviceType ?: "ANDROID"
        val cleanToken = dto.token.trim()

        if (cleanToken.isBlank()) {
            logger.warn("Rejecting empty FCM token registration for user $actualUserId")
            return
        }

        // Deduplicate & handle token rotation:
        // A single device token should map to the current authenticated user.
        val existing = deviceTokenRepository.findByToken(cleanToken)
        if (existing.isPresent) {
            val tokenEntity = existing.get()
            tokenEntity.userId = actualUserId
            tokenEntity.deviceType = platform
            tokenEntity.updatedAt = LocalDateTime.now()
            deviceTokenRepository.save(tokenEntity)
            logger.info("Updated existing FCM token for user $actualUserId (platform: $platform)")
        } else {
            val newToken = DeviceToken(
                userId = actualUserId,
                token = cleanToken,
                deviceType = platform,
                createdAt = LocalDateTime.now(),
                updatedAt = LocalDateTime.now()
            )
            deviceTokenRepository.save(newToken)
            logger.info("Registered new FCM token for user $actualUserId (platform: $platform)")
        }
    }

    @Transactional
    fun unregisterDeviceToken(token: String) {
        val cleanToken = token.trim()
        if (cleanToken.isNotBlank()) {
            deviceTokenRepository.deleteByToken(cleanToken)
            logger.info("Unregistered FCM device token: $cleanToken")
        }
    }

    fun getTokensForUser(userId: String): List<String> {
        val actualUserId = resolveUserId(userId)
        return deviceTokenRepository.findAllByUserId(actualUserId).map { it.token }
    }

    /**
     * Dispatches push notification to all devices registered to a specific user.
     * Guaranteed to never throw an exception or roll back the calling business transaction.
     */
    fun sendPushToUser(
        userId: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ) {
        try {
            val actualUserId = resolveUserId(userId)
            val tokens = deviceTokenRepository.findAllByUserId(actualUserId).map { it.token }.distinct()
            if (tokens.isEmpty()) {
                logger.debug("No FCM device tokens registered for user $actualUserId. Skipping push notification.")
                return
            }
            sendPushToTokens(tokens, title, body, data)
        } catch (e: Exception) {
            logger.warn("Non-fatal error sending push notification to user $userId: ${e.message}")
        }
    }

    /**
     * Dispatches push notification to all devices registered across multiple users.
     */
    fun sendPushToUsers(
        userIds: Collection<String>,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ) {
        try {
            val actualUserIds = userIds.map { resolveUserId(it) }.distinct()
            val allTokens = actualUserIds.flatMap { deviceTokenRepository.findAllByUserId(it) }
                .map { it.token }
                .distinct()

            if (allTokens.isEmpty()) {
                logger.debug("No registered tokens for the specified ${userIds.size} user(s).")
                return
            }
            sendPushToTokens(allTokens, title, body, data)
        } catch (e: Exception) {
            logger.warn("Non-fatal error in batch push notification: ${e.message}")
        }
    }

    private fun sendPushToTokens(
        tokens: List<String>,
        title: String,
        body: String,
        data: Map<String, String>
    ) {
        if (firebaseMessaging == null) {
            logger.info("[FCM-SIMULATION] Would send push to ${tokens.size} token(s): Title='$title', Body='$body', Data=$data")
            return
        }

        // Multicast message allows sending up to 500 tokens in a single batch
        tokens.chunked(500).forEach { batch ->
            try {
                val multicast = MulticastMessage.builder()
                    .addAllTokens(batch)
                    .setNotification(
                        Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build()
                    )
                    .putAllData(data)
                    .build()

                val response = firebaseMessaging.sendEachForMulticast(multicast)
                logger.info("FCM batch dispatched: ${response.successCount} succeeded, ${response.failureCount} failed out of ${batch.size}")

                if (response.failureCount > 0) {
                    val invalidTokens = mutableListOf<String>()
                    response.responses.forEachIndexed { index, sendResponse ->
                        if (!sendResponse.isSuccessful) {
                            val errorCode = sendResponse.exception?.messagingErrorCode
                            val token = batch[index]
                            if (errorCode == MessagingErrorCode.UNREGISTERED || errorCode == MessagingErrorCode.INVALID_ARGUMENT) {
                                invalidTokens.add(token)
                            } else {
                                logger.warn("FCM delivery failure for token $token: ${sendResponse.exception?.message}")
                            }
                        }
                    }

                    if (invalidTokens.isNotEmpty()) {
                        cleanupInvalidTokens(invalidTokens)
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to execute FCM multicast send: ${e.message}", e)
            }
        }
    }

    private fun cleanupInvalidTokens(tokens: List<String>) {
        try {
            tokens.forEach { token ->
                deviceTokenRepository.deleteByToken(token)
            }
            logger.info("Cleaned up ${tokens.size} stale/unregistered FCM token(s) from database.")
        } catch (e: Exception) {
            logger.warn("Failed to clean up stale FCM tokens: ${e.message}")
        }
    }
}
