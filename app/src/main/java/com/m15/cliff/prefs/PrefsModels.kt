package com.m15.cliff.prefs

data class DeviceLoginRequest(
    val deviceKey: String
)

data class DeviceLoginResponse(
    val token: String
)

data class PrefsRequest(
    val mood: String,
    val personality: String,
    val customPrompt: String
)

data class PrefsResponse(
    val mood: String,
    val personality: String,
    val customPrompt: String,
    val deviceKey: String,
    val updatedAt: Long
)
