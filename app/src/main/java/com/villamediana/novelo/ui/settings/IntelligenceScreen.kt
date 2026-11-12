package com.villamediana.novelo.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.ai.Api
import com.villamediana.novelo.local.LocalEngine
import com.villamediana.novelo.local.LocalModel
import com.villamediana.novelo.settings.NoveloSettings
import com.villamediana.novelo.settings.Source
import com.villamediana.novelo.ui.Nav
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.paper
import com.villamediana.novelo.ui.components.Group
import com.villamediana.novelo.ui.components.IconAction
import com.villamediana.novelo.ui.components.Pill
import com.villamediana.novelo.ui.components.SectionLabel
import com.villamediana.novelo.ui.components.Segmented
import com.villamediana.novelo.ui.components.formatBytes
import com.villamediana.novelo.ui.home.QuietIcon
import com.villamediana.novelo.ui.sheets.NoveloSheet
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import androidx.compose.ui.res.stringResource
import com.villamediana.novelo.R
import com.villamediana.novelo.ai.Provider
import com.villamediana.novelo.ai.Providers
import com.villamediana.novelo.settings.Lang
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.setup.OwnSetup

/**
 * Ajustes › Inteligência (opened in place): who thinks (the Novelo's own intelligence, or her API)
 * and only what that choice needs. Hearing is always on the phone (LFM3-Omni): its download is here.
 */
@Composable
fun IntelligenceSection() {
    val graph = NoveloApplication.graph
    val st = graph.settings
    val scope = rememberCoroutineScope()
    val s by st.now.collectAsStateWithLifecycle()
    val local by graph.localModel.state.collectAsStateWithLifecycle()
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var picking by remember { mutableStateOf(false) }
    var plans by remember { mutableStateOf(false) }

/** The models of her API, for the list. */
    fun loadModels() {
        scope.launch {
            val k = st.apiKey()
            val now = st.current()
            if (!now.apiReady || k.isNullOrBlank()) return@launch
            runCatching { withContext(Dispatchers.IO) { graph.api.models(now.endpoint(k)) } }.onSuccess { models = it }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Group(Modifier, {
                ThinkLine(
                    source = s.think,
                    model = s.thinkModel,
                    onSource = { src ->
                        scope.launch { st.setThink(src) }
                        if (src == Source.API && s.thinkModel.isBlank()) picking = true
                    },
                    onPick = { picking = true },
                )
            })

            // The Novelo's: the plan (trial, Novelo+), what is left today and this week, and the way to Novelo+.
            if (s.think == Source.NOVELO && graph.novelo.available) {
                Spacer(Modifier.height(10.dp))
                com.villamediana.novelo.ui.plans.PlanCard(onPlans = { plans = true })
            }

            SectionLabel(t(R.string.ai_on_phone))
            Group(Modifier, { LocalModelLine(graph.localModel, local, t(R.string.local_pack_hear)) })

            if (s.think == Source.API) {
                SectionLabel(t(R.string.ai_api))
                OwnSetup(showModels = false)
            }
        }
    }

    if (plans) com.villamediana.novelo.ui.plans.PlansSheet(onDismiss = { plans = false })

    if (picking) {
        ModelPicker(
            title = t(R.string.ai_model_thinks_title),
            current = s.thinkModel,
            models = models,
            settings = s,
            onLoad = { loadModels() },
            onPick = { m ->
                scope.launch {
                    st.setThinkModel(m)
                    graph.mind.retryPending()
                }
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/** A provider's name as she reads it ("Outro" / "Other" / "Otro" for an address of her own). */
internal fun providerName(p: Provider): String = if (p == Providers.OTHER) t(R.string.provider_other) else p.name

/** The model on the phone in a few words: what it needs or how it is. */
internal fun localShort(state: LocalModel.State): String = when {
    !LocalEngine.available -> t(R.string.local_not_in_version)
    state is LocalModel.State.Ready -> t(R.string.local_ready_short)
    state is LocalModel.State.Downloading -> t(R.string.local_downloading_short, NumberFormat.getPercentInstance(Lang.locale).format(state.fraction.toDouble()))
    state is LocalModel.State.Checking -> t(R.string.local_checking_short)
    state is LocalModel.State.Failed -> t(R.string.local_failed_short)
    else -> t(R.string.local_needs_download)
}

/** Who thinks: the Novelo's own intelligence or her API, on the right; under it, what is doing it (her API's model opens the list). */
@Composable
private fun ThinkLine(
    source: Source,
    model: String,
    onSource: (Source) -> Unit,
    onPick: () -> Unit,
) {
    val api = source == Source.API
    Row(
        Modifier.fillMaxWidth().clickable(enabled = api, onClick = onPick).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(t(R.string.ai_who_thinks), style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text)
            // The Novelo's needs no words under it (the choice says it); her API shows its model.
            if (api) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    model.ifBlank { t(R.string.ai_choose_model) },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (api && model.isBlank()) NoveloColors.Accent else NoveloColors.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (api) Icon(NoveloIcons.Chevron, null, tint = NoveloColors.TextFaint, modifier = Modifier.padding(start = 2.dp).size(14.dp))
            }
        }
        Segmented(
            listOf(t(R.string.ai_novelo), t(R.string.ai_api)),
            selected = if (api) 1 else 0,
            onSelect = { i -> onSource(if (i == 1) Source.API else Source.NOVELO) },
        )
    }
}

/**
 * What lives on the phone, as one thing ([name]): its state, and the one thing to do about it.
 * Off Wi-Fi, the download asks first: wait for it, or download anyway.
 */
@Composable
private fun LocalModelLine(model: LocalModel, state: LocalModel.State, name: String) {
    val total = model.totalBytes
    val percent = NumberFormat.getPercentInstance(Lang.locale)
    var askWifi by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Line(
            title = name,
            subtitle = when {
                !LocalEngine.available -> t(R.string.local_not_in_version)
                state is LocalModel.State.Ready -> t(R.string.local_ready)
                state is LocalModel.State.Downloading -> t(R.string.local_progress, percent.format(state.fraction.toDouble()), formatBytes(total))
                state is LocalModel.State.Checking -> t(R.string.local_checking)
                state is LocalModel.State.Failed -> state.message
                else -> t(R.string.local_size_offline, formatBytes(total))
            },
            subtitleColor = if (state is LocalModel.State.Failed) NoveloColors.Danger else NoveloColors.TextMuted,
        ) {
            if (LocalEngine.available) when (state) {
                LocalModel.State.Missing, is LocalModel.State.Failed -> IconAction(NoveloIcons.Download, t(R.string.local_download), {
                    if (model.onWifi()) model.download() else askWifi = true
                }, primary = true, size = 40.dp)
                is LocalModel.State.Downloading -> IconAction(NoveloIcons.Stop, t(R.string.local_stop_download), { model.cancel() }, size = 40.dp)
                LocalModel.State.Checking -> CircularProgressIndicator(Modifier.size(18.dp), color = NoveloColors.Accent, strokeWidth = 2.dp)
                // Hearing needs it: once here it stays (nothing to delete it with).
                LocalModel.State.Ready -> Unit
            }
        }
        if (askWifi && (state == LocalModel.State.Missing || state is LocalModel.State.Failed)) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Text(t(R.string.setup_local_no_wifi, formatBytes(total)), style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextMuted)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Pill(t(R.string.setup_local_wait_wifi), selected = true, onClick = { askWifi = false; model.download(wifiOnly = true) })
                    Pill(t(R.string.setup_local_download_anyway), selected = false, onClick = { askWifi = false; model.download() })
                }
            }
        }
        if (state is LocalModel.State.Downloading) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(NoveloColors.Surface2)) {
                Box(Modifier.fillMaxWidth(state.fraction.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(NoveloColors.Accent))
            }
            if (state.bytes == 0L && !model.onWifi()) {
                Text(t(R.string.setup_local_waiting_wifi), style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextMuted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(NoveloColors.Surface2).padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextFaint)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = NoveloColors.Text),
            cursorBrush = SolidColor(NoveloColors.Accent),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The model for a job: typed, or picked from her API's list (filtered by what is typed). */
@Composable
internal fun ModelPicker(
    title: String,
    current: String,
    models: List<String>,
    settings: NoveloSettings,
    onLoad: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf(current) }
    LaunchedEffect(Unit) { if (models.isEmpty() && settings.apiReady) onLoad() }
    NoveloSheet(onDismissRequest = onDismiss) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = NoveloColors.Text, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(value = typed, onChange = { typed = it }, hint = t(R.string.ai_model_name_hint)) }
            AnimatedVisibility(typed.isNotBlank()) {
                IconAction(NoveloIcons.Check, t(R.string.ai_use), { onPick(typed.trim()) }, modifier = Modifier.padding(start = 8.dp), primary = true)
            }
        }
        if (!settings.apiReady) {
            Text(t(R.string.ai_fill_to_load), style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextMuted, modifier = Modifier.padding(24.dp))
        }
        val filtered = models.filter { typed.isBlank() || it.contains(typed.trim(), ignoreCase = true) || it == current }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(top = 8.dp)) {
            items(filtered, key = { it }) { m ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(m) }.padding(horizontal = 24.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(m, style = MaterialTheme.typography.bodyLarge, color = NoveloColors.Text, modifier = Modifier.weight(1f))
                    if (m == current) Icon(NoveloIcons.Check, null, tint = NoveloColors.Accent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
