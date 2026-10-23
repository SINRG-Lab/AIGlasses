package com.example.aiglasses.model

data class LogEntry(
    val tag: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)
