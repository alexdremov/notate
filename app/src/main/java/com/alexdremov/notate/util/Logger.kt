package com.alexdremov.notate.util

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object Logger {
    private const val TAG_PREFIX = "Notate"
    private const val DEFAULT_TAG = "App"

    private val _userEvents =
        MutableSharedFlow<UserEvent>(
            replay = 0,
            extraBufferCapacity = 20,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val userEvents: SharedFlow<UserEvent> = _userEvents.asSharedFlow()

    data class UserEvent(
        val message: String,
        val level: Level,
        val throwable: Throwable? = null,
    )

    enum class Level(
        val priority: Int,
    ) {
        DEBUG(0),
        INFO(1),
        WARNING(2),
        ERROR(3),
        NONE(4),
    }

    private var minLogLevelToShow: Level = Level.NONE

    fun setMinLogLevelToShow(level: Level) {
        minLogLevelToShow = level
    }

    fun getMinLogLevelToShow(): Level = minLogLevelToShow

    private fun formatTag(tag: String?): String = if (tag.isNullOrBlank()) "$TAG_PREFIX.$DEFAULT_TAG" else "$TAG_PREFIX.$tag"

    fun showToUser(message: String) {
        _userEvents.tryEmit(UserEvent(message, Level.INFO))
    }

    fun d(
        tag: String?,
        message: String,
    ) {
        Log.d(formatTag(tag), message)
        if (minLogLevelToShow.priority <= Level.DEBUG.priority) {
            _userEvents.tryEmit(UserEvent(message, Level.DEBUG))
        }
    }

    fun i(
        tag: String?,
        message: String,
    ) {
        Log.i(formatTag(tag), message)
        if (minLogLevelToShow.priority <= Level.INFO.priority) {
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
        if (minLogLevelToShow.priority <= Level.WARNING.priority) {
            _userEvents.tryEmit(UserEvent(message, Level.WARNING, throwable))
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

        if (showToUser || minLogLevelToShow.priority <= Level.ERROR.priority) {
            _userEvents.tryEmit(UserEvent(message, Level.ERROR, throwable))
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
