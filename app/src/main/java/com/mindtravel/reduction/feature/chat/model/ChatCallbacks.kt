package com.mindtravel.reduction.feature.chat.model

interface ChatCallbacks {
    /** The model finished processing this turn's initial input, before any tool exchanges. */
    fun onInitialInputAccepted() = Unit
    fun onToken(text: String)
    /** [summary] is the one line shown for this call; [announce] asks for it to be said aloud too. */
    fun onToolStarted(name: String, summary: String, arguments: String, announce: Boolean)
    fun onToolFinished(name: String, durationMs: Long, success: Boolean, result: String)
    /** The request did not get through and is being sent again; [attempt] counts from 2. */
    fun onRetrying(attempt: Int, attempts: Int) = Unit
    fun onCompleted(text: String)
    fun onFailed(message: String)
}
