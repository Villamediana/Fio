package com.villamediana.novelo

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.Composable

/**
 * The Google Play build: Play updates the app (an app from Play may not update itself), so none of
 * the GitHub build's updater is here (src/github has it). The same calls, doing nothing.
 */
object Store {
    fun early(app: Application) {}

    fun started(app: Application) {}

    fun opened(intent: Intent?) {}

    @Composable
    fun Host() {}

    val settingsLine: (@Composable () -> Unit)? get() = null
}
