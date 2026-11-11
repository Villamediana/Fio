package com.villamediana.fio.ui

import androidx.compose.runtime.mutableStateListOf

enum class Filter { TODAY, TASKS, NOTES, DIARY, ALL }

sealed interface Screen {
    data object Home : Screen
    data class Library(val filter: Filter, val search: Boolean = false, val query: String = "") : Screen
    data class Entry(val id: Long) : Screen
    data object Settings : Screen
    data object Camera : Screen
    data class Graph(val topicId: Long? = null) : Screen
}

/** A small back stack: the main screen at the bottom, always. */
class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val top: Screen get() = stack.last()

    fun go(screen: Screen) {
        if (stack.last() == screen) return
        // Library over library: replace (filters are tabs, not pages).
        if (screen is Screen.Library && stack.last() is Screen.Library) stack[stack.size - 1] = screen else stack.add(screen)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        return true
    }

    fun home() {
        while (stack.size > 1) stack.removeAt(stack.size - 1)
    }
}
