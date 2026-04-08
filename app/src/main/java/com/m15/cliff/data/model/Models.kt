package com.m15.cliff.data.model

data class ChatMessage(
    val role: String, // "user" | "assistant"
    val text: String,
    val ts: Long = System.currentTimeMillis()
)
