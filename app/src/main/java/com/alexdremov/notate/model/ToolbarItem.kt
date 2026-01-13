package com.alexdremov.notate.model

import kotlinx.serialization.Serializable

@Serializable
sealed class ToolbarItem {
    abstract val id: String

    @Serializable
    data class Pen(val penTool: PenTool) : ToolbarItem() {
        override val id: String get() = penTool.id
    }

    @Serializable
    data class Eraser(val penTool: PenTool) : ToolbarItem() {
        override val id: String get() = penTool.id
    }

    @Serializable
    data class Select(val penTool: PenTool) : ToolbarItem() {
        override val id: String get() = penTool.id
    }

    @Serializable
    data class Action(val actionType: ActionType) : ToolbarItem() {
        override val id: String get() = actionType.name
    }

    @Serializable
    data class Widget(val widgetType: WidgetType) : ToolbarItem() {
        override val id: String get() = widgetType.name
    }
}

@Serializable
enum class ActionType {
    UNDO,
    REDO
}

@Serializable
enum class WidgetType {
    PAGE_NAVIGATION
}
