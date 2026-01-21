package com.alexdremov.notate.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object SaveStatusManager {
    private val _savingFiles = MutableStateFlow<Set<String>>(emptySet())
    val savingFiles: StateFlow<Set<String>> = _savingFiles.asStateFlow()

    fun startSaving(path: String) {
        _savingFiles.update { it + path }
    }

    fun finishSaving(path: String) {
        _savingFiles.update { it - path }
    }

    fun isSaving(path: String): Boolean = _savingFiles.value.contains(path)
}
