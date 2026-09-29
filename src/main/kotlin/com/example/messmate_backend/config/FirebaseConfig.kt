package com.example.messmate_backend.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

@Configuration
class FirebaseConfig {

    private val logger = LoggerFactory.getLogger(FirebaseConfig::class.java)

    @Bean
    fun firebaseMessaging(): FirebaseMessaging? {
        if (FirebaseApp.getApps().isNotEmpty()) {
            return FirebaseMessaging.getInstance()
        }

        val credentialsStream: InputStream? = resolveCredentialsStream()

        return if (credentialsStream != null) {
            try {
                credentialsStream.use { stream ->
                    val options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(stream))
                        .build()
                    val app = FirebaseApp.initializeApp(options)
                    logger.info("Firebase Admin SDK initialized successfully for project: ${app.options.projectId}")
                    FirebaseMessaging.getInstance(app)
                }
            } catch (e: Exception) {
                logger.error("Failed to initialize Firebase Admin SDK from credentials: ${e.message}", e)
                null
            }
        } else {
            logger.warn("No Firebase Admin credentials found (checked FIREBASE_CREDENTIALS_PATH, GOOGLE_APPLICATION_CREDENTIALS, FIREBASE_CONFIG_JSON). FCM notifications will run in simulation mode.")
            null
        }
    }

    private fun resolveCredentialsStream(): InputStream? {
        // 1. Env var FIREBASE_CREDENTIALS_PATH
        val path = System.getenv("FIREBASE_CREDENTIALS_PATH")
        if (!path.isNullOrBlank() && File(path).exists()) {
            logger.info("Loading Firebase credentials from FIREBASE_CREDENTIALS_PATH: $path")
            return FileInputStream(path)
        }

        // 2. Env var GOOGLE_APPLICATION_CREDENTIALS
        val gPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS")
        if (!gPath.isNullOrBlank() && File(gPath).exists()) {
            logger.info("Loading Firebase credentials from GOOGLE_APPLICATION_CREDENTIALS: $gPath")
            return FileInputStream(gPath)
        }

        // 3. Env var FIREBASE_CONFIG_JSON
        val json = System.getenv("FIREBASE_CONFIG_JSON")
        if (!json.isNullOrBlank()) {
            logger.info("Loading Firebase credentials from FIREBASE_CONFIG_JSON environment variable")
            return ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8))
        }

        // 4. File in working directory
        val localFile = File("firebase-service-account.json")
        if (localFile.exists()) {
            logger.info("Loading Firebase credentials from local file: ${localFile.absolutePath}")
            return FileInputStream(localFile)
        }

        return null
    }
}
