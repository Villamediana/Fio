package com.villamediana.fio.ui.settings

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
import com.villamediana.fio.FioApplication
import com.villamediana.fio.ai.Api
import com.villamediana.fio.local.LocalEngine
import com.villamediana.fio.local.LocalModel
import com.villamediana.fio.settings.FioSettings
import com.villamediana.fio.settings.Source
import com.villamediana.fio.ui.Nav
import com.villamediana.fio.ui.components.FioIcons
import com.villamediana.fio.ui.components.paper
import com.villamediana.fio.ui.components.Group
import com.villamediana.fio.ui.components.IconAction
import com.villamediana.fio.ui.components.Pill
import com.villamediana.fio.ui.components.SectionLabel
import com.villamediana.fio.ui.components.Segmented
import com.villamediana.fio.ui.components.formatBytes
import com.villamediana.fio.ui.home.QuietIcon
import com.villamediana.fio.ui.sheets.FioSheet
import com.villamediana.fio.ui.theme.FioColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import androidx.compose.ui.res.stringResource
import com.villamediana.fio.R
import com.villamediana.fio.ai.Provider
import com.villamediana.fio.ai.Providers
import com.villamediana.fio.settings.Lang
import com.villamediana.fio.settings.t
import com.villamediana.fio.ui.setup.OwnSetup

/**
 * Ajustes › Inteligência: who hears and who thinks (the phone or her API), and only what that
 * choice needs — the model on the phone, or whose API and its key (tested by itself, the same as
 * in the first steps).
 */
@Composable
fun IntelligenceScreen(nav: Nav) {
    val graph = FioApplication.graph
    val st = graph.settings
    val scope = rememberCoroutineScope()
    val s by st.now.collectAsStateWithLifecycle()
    val local by graph.localModel.state.collectAsStateWithLifecycle()
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var picking by remember { mutableStateOf<Pick?>(null) }
    val usesApi = s.hear == Source.API || s.think == Source.API
    val usesPhone = s.hear == Source.LOCAL || s.think == Source.LOCAL
    val provider = s.provider

    /** The models of her API, for the list. */
    fun loadModels() {
        scope.launch {
            val k = st.apiKey()
            val now = st.current()
            if (!now.apiReady || k.isNullOrBlank()) return@launch
            runCatching { withContext(Dispatchers.IO) { graph.api.models(now.endpoint(k)) } }.onSuccess { models = it }
        }
    }

    Column(Modifier.fillMaxSize().paper().statusBarsPadding().navigationBarsPadding().imePadding()) {
        PageTop(t(R.string.ai_title)) { nav.back() }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(10.dp))
            Group(
                Modifier,
                {
                    WorkLine(
                        title = t(R.string.ai_hear),
                        source = s.hear,
                        model = s.hearModel,
                        local = local,
                        localName = graph.localModel.spec.name,
                        // A provider that doesn't hear: hearing stays on the phone.
                        apiNote = if (!provider.hears && s.think == Source.API) t(R.string.ai_provider_no_audio, providerName(provider)) else null,
                        onSource = { src ->
                            scope.launch { st.setHear(src) }
                            if (src == Source.API && s.hearModel.isBlank()) picking = Pick.HEAR
                        },
                        onPick = { picking = Pick.HEAR },
                    )
                },
                {
                    WorkLine(
                        title = t(R.string.ai_think),
                        source = s.think,
                        model = s.thinkModel,
                        local = local,
                        localName = graph.localModel.spec.name,
                        onSource = { src ->
                            scope.launch { st.setThink(src) }
                            if (src == Source.API && s.thinkModel.isBlank()) picking = Pick.THINK
                        },
                        onPick = { picking = Pick.THINK },
                    )
                },
            )

            if (usesPhone) {
                SectionLabel(t(R.string.ai_on_phone))
                Group(Modifier, { LocalModelLine(graph.localModel, local) })
            }

            if (usesApi) {
                SectionLabel(t(R.string.ai_api))
                OwnSetup(showModels = false)
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    picking?.let { job ->
        ModelPicker(
            title = t(if (job == Pick.HEAR) R.string.ai_model_hears_title else R.string.ai_model_thinks_title),
            current = if (job == Pick.HEAR) s.hearModel else s.thinkModel,
            models = models,
            settings = s,
            onLoad = { loadModels() },
            onPick = { m ->
                scope.launch {
                    if (job == Pick.HEAR) st.setHearModel(m) else st.setThinkModel(m)
                    graph.mind.retryPending()
                }
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/** A provider's name as she reads it ("Outro" / "Other" / "Otro" for an address of her own). */
internal fun providerName(p: Provider): String = if (p == Providers.OTHER) t(R.string.provider_other) else p.name

private enum class Pick { HEAR, THINK }

/** The model on the phone in a few words: what it needs or how it is. */
internal fun localShort(state: LocalModel.State): String = when {
    !LocalEngine.available -> t(R.string.local_not_in_version)
    state is LocalModel.State.Ready -> t(R.string.local_ready_short)
    state is LocalModel.State.Downloading -> t(R.string.local_downloading_short, NumberFormat.getPercentInstance(Lang.locale).format(state.fraction.toDouble()))
    state is LocalModel.State.Checking -> t(R.string.local_checking_short)
    state is LocalModel.State.Failed -> t(R.string.local_failed_short)
    else -> t(R.string.local_needs_download)
}

/**
 * One job (hear or think): where it runs on the right; under the name, the model doing it.
 * [apiNote]: the API can't do this job (its provider doesn't hear) — said instead of the choice.
 */
@Composable
private fun WorkLine(
    title: String,
    source: Source,
    model: String,
    local: LocalModel.State,
    localName: String,
    onSource: (Source) -> Unit,
    onPick: () -> Unit,
    apiNote: String? = null,
) {
    val api = source == Source.API && apiNote == null
    Row(
        Modifier.fillMaxWidth().clickable(enabled = api, onClick = onPick).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = FioColors.Text)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    apiNote ?: if (api) model.ifBlank { t(R.string.ai_choose_model) } else "$localName · ${localShort(local)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (api && model.isBlank()) FioColors.Accent else FioColors.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (api) Icon(FioIcons.Chevron, null, tint = FioColors.TextFaint, modifier = Modifier.padding(start = 2.dp).size(14.dp))
            }
        }
        if (apiNote == null) {
            Segmented(
                listOf(t(R.string.ai_phone), t(R.string.ai_api)),
                selected = if (api) 1 else 0,
                onSelect = { i -> onSource(if (i == 1) Source.API else Source.LOCAL) },
            )
        }
    }
}

/** The model that lives on the phone (it hears, thinks and sees): its state, and the one thing to do about it. */
@Composable
private fun LocalModelLine(model: LocalModel, state: LocalModel.State) {
    val total = model.totalBytes
    val percent = NumberFormat.getPercentInstance(Lang.locale)
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Line(
            title = model.spec.name,
            subtitle = when {
                !LocalEngine.available -> t(R.string.local_not_in_version)
                state is LocalModel.State.Ready -> t(R.string.local_ready)
                state is LocalModel.State.Downloading -> t(R.string.local_progress, percent.format(state.fraction.toDouble()), formatBytes(total))
                state is LocalModel.State.Checking -> t(R.string.local_checking)
                state is LocalModel.State.Failed -> state.message
                else -> t(R.string.local_size_offline, formatBytes(total))
            },
            subtitleColor = if (state is LocalModel.State.Failed) FioColors.Danger else FioColors.TextMuted,
        ) {
            if (LocalEngine.available) when (state) {
                LocalModel.State.Missing, is LocalModel.State.Failed -> IconAction(FioIcons.Download, t(R.string.local_download), { model.download() }, primary = true, size = 40.dp)
                is LocalModel.State.Downloading -> IconAction(FioIcons.Stop, t(R.string.local_stop_download), { model.cancel() }, size = 40.dp)
                LocalModel.State.Checking -> CircularProgressIndicator(Modifier.size(18.dp), color = FioColors.Accent, strokeWidth = 2.dp)
                LocalModel.State.Ready -> IconAction(FioIcons.Trash, t(R.string.local_delete), { model.delete() }, size = 40.dp)
            }
        }
        if (state is LocalModel.State.Downloading) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(FioColors.Surface2)) {
                Box(Modifier.fillMaxWidth(state.fraction.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(FioColors.Accent))
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(FioColors.Surface2).padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = FioColors.TextFaint)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = FioColors.Text),
            cursorBrush = SolidColor(FioColors.Accent),
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
    settings: FioSettings,
    onLoad: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf(current) }
    LaunchedEffect(Unit) { if (models.isEmpty() && settings.apiReady) onLoad() }
    FioSheet(onDismissRequest = onDismiss) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = FioColors.Text, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(value = typed, onChange = { typed = it }, hint = t(R.string.ai_model_name_hint)) }
            AnimatedVisibility(typed.isNotBlank()) {
                IconAction(FioIcons.Check, t(R.string.ai_use), { onPick(typed.trim()) }, modifier = Modifier.padding(start = 8.dp), primary = true)
            }
        }
        if (!settings.apiReady) {
            Text(t(R.string.ai_fill_to_load), style = MaterialTheme.typography.bodySmall, color = FioColors.TextMuted, modifier = Modifier.padding(24.dp))
        }
        val filtered = models.filter { typed.isBlank() || it.contains(typed.trim(), ignoreCase = true) || it == current }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(top = 8.dp)) {
            items(filtered, key = { it }) { m ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(m) }.padding(horizontal = 24.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(m, style = MaterialTheme.typography.bodyLarge, color = FioColors.Text, modifier = Modifier.weight(1f))
                    if (m == current) Icon(FioIcons.Check, null, tint = FioColors.Accent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
