package com.example.notate.ui.export

sealed interface ExportAction {
    data class Export(
        val isVector: Boolean,
    ) : ExportAction

    data class Share(
        val isVector: Boolean,
    ) : ExportAction
}
