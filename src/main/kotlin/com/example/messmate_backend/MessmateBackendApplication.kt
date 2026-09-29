package com.example.messmate_backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class MessmateBackendApplication

fun main(args: Array<String>) {
	runApplication<MessmateBackendApplication>(*args)
}
