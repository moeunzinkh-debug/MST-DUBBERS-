package com.example.data.api

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Why a subtitle line could not be voiced. */
class TtsException(
    message: String,
    val kind: Kind,
    /** How long the server asked us to wait before trying again, if it said so. */
    val retryAfterSec: Long? = null,
    cause: Throwable? = null
) : IOException(message, cause) {

    enum class Kind {
        /** The line has no words to read (only symbols such as ♪ or ...). Retrying won't help. */
        NOTHING_TO_SPEAK,

        /** Too many requests: wait, then try again. */
        RATE_LIMITED,

        /** Server busy or failing, timeout, or no connection: trying again later may work. */
        TEMPORARY,

        /** Anything else (wrong server address, missing API key, ...). */
        PERMANENT
    }

    /** True if trying the same line again later may work. */
    val isRetryable: Boolean get() = kind == Kind.RATE_LIMITED || kind == Kind.TEMPORARY

    companion object {
        /** Wraps any error from a TTS call as a [TtsException], guessing whether it is temporary. */
        fun from(error: Throwable): TtsException = when (error) {
            is TtsException -> error
            is InterruptedIOException, is ConnectException, is UnknownHostException, is SocketException,
            is SSLException -> TtsException(
                "No connection to the voice server (${error.message ?: error.javaClass.simpleName})",
                Kind.TEMPORARY,
                cause = error
            )
            else -> TtsException(error.message ?: error.javaClass.simpleName, Kind.PERMANENT, cause = error)
        }

        /** Classifies an HTTP error status from a TTS server. */
        fun forHttpStatus(code: Int, detail: String, retryAfterSec: Long? = null): TtsException {
            val text = if (detail.isBlank()) "HTTP $code" else "HTTP $code: $detail"
            return when {
                code == 429 -> TtsException("Too many requests ($text)", Kind.RATE_LIMITED, retryAfterSec)
                code == 408 || code == 425 || code >= 500 -> TtsException("Voice server error ($text)", Kind.TEMPORARY, retryAfterSec)
                else -> TtsException("Voice server refused the line ($text)", Kind.PERMANENT)
            }
        }
    }
}

/** Decides whether a line has anything a TTS voice can read. */
object SpeakableText {
    private val markupTag = Regex("<[^>]+>")
    private val genderSign = Regex("^[\u2642\u2640]\uFE0F?\\s*")
    private val voiceTag = Regex("^\\[(piseth|sreymom|male|female)]\\s*", RegexOption.IGNORE_CASE)

    /**
     * Removes what the Edge TTS server strips before speaking: markup tags like `<i>`, a leading
     * ♂/♀ sign and a leading `[piseth]`-style voice tag.
     */
    fun clean(text: String): String {
        var t = markupTag.replace(text, "").trim()
        t = genderSign.replace(t, "")
        t = voiceTag.replace(t, "")
        return t.trim()
    }

    /** True if [text] has at least one letter or digit, in any script (Khmer included). */
    fun hasWords(text: String): Boolean = clean(text).any { Character.isLetterOrDigit(it) }
}
