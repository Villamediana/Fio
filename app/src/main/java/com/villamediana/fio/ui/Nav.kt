package com.villamediana.fio.ui

import androidx.compose.runtime.mutableStateListOf

/** The tabs of the library; LINKS is the drawing of what connects to what. */
enum class Filter { TODAY, TASKS, NOTES, DIARY, ALL, LINKS }

sealed interface Screen {
    data object Home : Screen
    /** [topic] opens the LINKS tab with that knot already touched. */
    data class Library(val filter: Filter, val search: Boolean = false, val query: String = "", val topic: Long? = null) : Screen
    data class Entry(val id: Long) : Screen
    data object Settings : Screen
    data object Theme : Screen
    data object Camera : Screen
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
