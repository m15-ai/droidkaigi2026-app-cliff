package com.m15.cliff.net

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction for an LLM streaming client.
 * The Claude implementation uses HTTP SSE streaming (stateless per-request).
 */
interface LlmClient {

    /**
     * Sends user text along with conversation history and streams back the response.
     * Each call is a standalone request — the client does not maintain server-side state.
     *
     * @param text The new user message
     * @param history Previous messages as (role, content) pairs where role is "user" or "assistant"
     * @param systemMessage The system prompt to use
     * @return Flow of streaming events
     */
    fun sendUserText(
        text: String,
        history: List<Pair<String, String>> = emptyList(),
        systemMessage: String = DEFAULT_SYSTEM_MESSAGE
    ): Flow<Event>

    /** Cancel an in-flight response if supported. */
    fun cancelResponse()

    /** Release resources. */
    fun close()

    sealed interface Event {
        data class TextDelta(val text: String) : Event
        data class TextCompleted(val text: String) : Event
        data class Error(val t: Throwable) : Event
    }

    companion object {
        const val DEFAULT_SYSTEM_MESSAGE =
            "You are a helpful voice assistant. Your responses are spoken aloud via text-to-speech. " +
            "Keep responses short and conversational — 1 to 3 sentences max. " +
            "Never use bullet points, numbered lists, markdown, emojis, or special formatting. " +
            "Speak in plain, natural sentences like a real conversation. " +
            "If a topic needs more detail, offer to explain further rather than dumping everything at once."
    }
}
