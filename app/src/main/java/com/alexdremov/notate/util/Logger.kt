package com.alexdremov.notate.util

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object Logger {
    private const val TAG_PREFIX = "BooxVibes"
    private const val DEFAULT_TAG = "App"

    private val _userEvents =
        MutableSharedFlow<UserEvent>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val userEvents: SharedFlow<UserEvent> = _userEvents.asSharedFlow()

    data class UserEvent(
        val message: String,
        val level: Level,
    )

    enum class Level {
        DEBUG,
        INFO,
        WARNING,
        ERROR,
        NONE,
    }

    private var minLogLevelToShow: Level = Level.NONE

    fun setMinLogLevelToShow(level: Level) {
        minLogLevelToShow = level
    }

    fun getMinLogLevelToShow(): Level = minLogLevelToShow

    private fun formatTag(tag: String?): String = if (tag.isNullOrEmpty()) "$TAG_PREFIX.$DEFAULT_TAG" else "$TAG_PREFIX.$tag"

    fun d(
        tag: String?,
        message: String,
    ) {
        Log.d(formatTag(tag), message)
        if (Level.DEBUG >= minLogLevelToShow) {
            _userEvents.tryEmit(UserEvent(message, Level.DEBUG))
        }
    }

    fun i(
        tag: String?,
        message: String,
    ) {
        Log.i(formatTag(tag), message)
        if (Level.INFO >= minLogLevelToShow) {
            _userEvents.tryEmit(UserEvent(message, Level.INFO))
        }
    }

    fun w(
        tag: String?,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (throwable != null) {
            Log.w(formatTag(tag), message, throwable)
        } else {
            Log.w(formatTag(tag), message)
        }
        if (Level.WARNING >= minLogLevelToShow) {
            _userEvents.tryEmit(UserEvent(message, Level.WARNING))
        }
    }

    fun e(
        tag: String?,
        message: String,
        throwable: Throwable? = null,
        showToUser: Boolean = false,
    ) {
        val finalTag = formatTag(tag)
        if (throwable != null) {
            Log.e(finalTag, message, throwable)
        } else {
            Log.e(finalTag, message)
        }

        if (showToUser || Level.ERROR >= minLogLevelToShow) {
            _userEvents.tryEmit(UserEvent(message, Level.ERROR))
        }
    }

    // Overloads for convenience without tag
    fun d(message: String) = d(null, message)

    fun i(message: String) = i(null, message)

    fun w(
        message: String,
        throwable: Throwable? = null,
    ) = w(null, message, throwable)

    fun e(
        message: String,
        throwable: Throwable? = null,
        showToUser: Boolean = false,
    ) = e(null, message, throwable, showToUser)
}
