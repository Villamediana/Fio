package com.villamediana.novelo

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.Composable
import com.villamediana.novelo.billing.Billing
import com.villamediana.novelo.billing.PlayBilling
import com.villamediana.novelo.settings.Source
import kotlinx.coroutines.launch

/**
 * The Google Play build: Play updates the app (an app from Play may not update itself), so none of
 * the GitHub build's updater is here (src/github has it); Novelo+ is sold by Google Play.
 */
object Store {
    /** Novelo+ through Google Play, checked by the Novelo's server. */
    val billing: Billing by lazy { PlayBilling(NoveloApplication.graph.appContext, NoveloApplication.graph.novelo) }

    fun early(app: Application) {}

    /** What she already bought on Google Play (another phone, reinstalled) goes back to the server. */
    fun started(app: Application) {
        val graph = NoveloApplication.graph
        graph.scope.launch {
            if (graph.novelo.available && graph.settings.current().think == Source.NOVELO) runCatching { billing.restore() }
        }
    }

    fun opened(intent: Intent?) {}

    @Composable
    fun Host() {}

    val settingsLine: (@Composable () -> Unit)? get() = null
}
