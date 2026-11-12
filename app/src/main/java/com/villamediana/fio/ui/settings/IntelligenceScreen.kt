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

/** What the API check said: a few words, and whether it went well. */
private data class Note(val text: String, val ok: Boolean? = null)

/**
 * Ajustes › Inteligência: who hears and who thinks (the phone or an API), and only what that
 * choice needs — the model on the phone, or the API's address and key. The connection is tested
 * by itself when they are filled in or changed.
 */
@Composable
fun IntelligenceScreen(nav: Nav) {
    val graph = FioApplication.graph
    val st = graph.settings
    val scope = rememberCoroutineScope()
    val s by st.now.collectAsStateWithLifecycle()
    val local by graph.localModel.state.collectAsStateWithLifecycle()

    var base by remember { mutableStateOf(s.apiBase) }
    var key by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Note?>(null) }
    var picking by remember { mutableStateOf<Pick?>(null) }
    val usesApi = s.hear == Source.API || s.think == Source.API
    val usesPhone = s.hear == Source.LOCAL || s.think == Source.LOCAL

    LaunchedEffect(s.apiBase) { if (base.isEmpty()) base = s.apiBase }
    // What is typed is kept a beat after typing; the key is kept when leaving too.
    LaunchedEffect(base) { delay(500); if (base.trim().trimEnd('/') != s.apiBase) st.setApiBase(base) }
    val pendingKey by rememberUpdatedState(key)
    DisposableEffect(Unit) {
        onDispose { if (pendingKey.isNotBlank()) graph.scope.launch { st.setApiKey(pendingKey); graph.mind.retryPending() } }
    }

    /** Keeps what was typed and asks the API for its models: that is the connection test. */
    suspend fun connect() {
        loading = true
        if (base.isNotBlank()) st.setApiBase(base)
        if (key.isNotBlank()) { st.setApiKey(key); key = "" }
        val k = st.apiKey()
        val b = st.current().apiBase
        if (b.isBlank() || k.isNullOrBlank()) {
            note = null
            loading = false
            return
        }
        runCatching { withContext(Dispatchers.IO) { graph.api.models(Api.Endpoint(b, k)) } }
            .onSuccess { models = it; note = if (it.isEmpty()) Note("conectado", ok = true) else Note("conectado · ${it.size} modelos", ok = true) }
            .onFailure { note = Note("não conectou · ${it.message}", ok = false) }
        loading = false
        graph.mind.retryPending()
    }

    // The test goes by itself: on opening (when the API is in use) and a moment after the address or the key change.
    LaunchedEffect(usesApi, base, key) {
        if (!usesApi) return@LaunchedEffect
        if (base.isBlank() && key.isBlank() && !s.apiReady) return@LaunchedEffect
        delay(if (key.isBlank() && base.trim().trimEnd('/') == s.apiBase) 0 else 900)
        connect()
    }

    Column(Modifier.fillMaxSize().paper().statusBarsPadding().navigationBarsPadding().imePadding()) {
        PageTop("Inteligência") { nav.back() }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(10.dp))
            Group(
                Modifier,
                {
                    WorkLine(
                        title = "Ouvir",
                        source = s.hear,
                        model = s.hearModel,
                        local = local,
                        localName = graph.localModel.spec.name,
                        onSource = { src ->
                            scope.launch { st.setHear(src) }
                            if (src == Source.API && s.hearModel.isBlank()) picking = Pick.HEAR
                        },
                        onPick = { picking = Pick.HEAR },
                    )
                },
                {
                    WorkLine(
                        title = "Pensar",
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
                SectionLabel("No celular")
                Group(Modifier, { LocalModelLine(graph.localModel, local) })
            }

            if (usesApi) {
                SectionLabel("API")
                Group(
                    Modifier,
                    { FieldLine("Endereço", base, { base = it }, hint = "https://…/v1", keyboard = KeyboardType.Uri) },
                    {
                        FieldLine(
                            "Chave", key, { key = it },
                            hint = if (s.hasApiKey) "guardada" else "cole a chave",
                            hintColor = if (s.hasApiKey) FioColors.TextMuted else FioColors.TextFaint,
                            secret = true,
                        )
                    },
                )
                Row(Modifier.padding(start = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (loading) CircularProgressIndicator(Modifier.size(12.dp), color = FioColors.Accent, strokeWidth = 1.5.dp)
                    Text(
                        when {
                            loading -> "testando a conexão…"
                            note != null -> note!!.text
                            !s.apiReady -> "falta o endereço ou a chave"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (note?.ok) { true -> FioColors.Success; false -> FioColors.Danger; null -> FioColors.TextMuted },
                        modifier = Modifier.padding(start = if (loading) 8.dp else 0.dp),
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    picking?.let { job ->
        ModelPicker(
            title = if (job == Pick.HEAR) "Modelo que transcreve" else "Modelo que organiza",
            current = if (job == Pick.HEAR) s.hearModel else s.thinkModel,
            models = models,
            settings = s,
            onLoad = { scope.launch { connect() } },
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

private enum class Pick { HEAR, THINK }

/** The model on the phone in a few words: what it needs or how it is. */
internal fun localShort(state: LocalModel.State): String = when {
    !LocalEngine.available -> "não vem nesta versão"
    state is LocalModel.State.Ready -> "pronto"
    state is LocalModel.State.Downloading -> "baixando " + NumberFormat.getPercentInstance().format(state.fraction.toDouble())
    state is LocalModel.State.Checking -> "conferindo"
    state is LocalModel.State.Failed -> "não baixou"
    else -> "precisa baixar"
}

/** One job (hear or think): where it runs on the right; under the name, the model doing it. */
@Composable
private fun WorkLine(
    title: String,
    source: Source,
    model: String,
    local: LocalModel.State,
    localName: String,
    onSource: (Source) -> Unit,
    onPick: () -> Unit,
) {
    val api = source == Source.API
    Row(
        Modifier.fillMaxWidth().clickable(enabled = api, onClick = onPick).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = FioColors.Text)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    if (api) model.ifBlank { "escolher modelo" } else "$localName · ${localShort(local)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (api && model.isBlank()) FioColors.Accent else FioColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (api) Icon(FioIcons.Chevron, null, tint = FioColors.TextFaint, modifier = Modifier.padding(start = 2.dp).size(14.dp))
            }
        }
        Segmented(
            listOf("Celular", "API"),
            selected = if (api) 1 else 0,
            onSelect = { i -> onSource(if (i == 1) Source.API else Source.LOCAL) },
        )
    }
}

/** The model that lives on the phone (it hears, thinks and sees): its state, and the one thing to do about it. */
@Composable
private fun LocalModelLine(model: LocalModel, state: LocalModel.State) {
    val total = model.totalBytes
    val percent = NumberFormat.getPercentInstance()
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Line(
            title = model.spec.name,
            subtitle = when {
                !LocalEngine.available -> "não vem nesta versão"
                state is LocalModel.State.Ready -> "pronto · funciona sem internet"
                state is LocalModel.State.Downloading -> percent.format(state.fraction.toDouble()) + " de " + formatBytes(total)
                state is LocalModel.State.Checking -> "conferindo o arquivo…"
                state is LocalModel.State.Failed -> state.message
                else -> formatBytes(total) + " · funciona sem internet"
            },
            subtitleColor = if (state is LocalModel.State.Failed) FioColors.Danger else FioColors.TextMuted,
        ) {
            if (LocalEngine.available) when (state) {
                LocalModel.State.Missing, is LocalModel.State.Failed -> IconAction(FioIcons.Download, "Baixar", { model.download() }, primary = true, size = 40.dp)
                is LocalModel.State.Downloading -> IconAction(FioIcons.Stop, "Parar o download", { model.cancel() }, size = 40.dp)
                LocalModel.State.Checking -> CircularProgressIndicator(Modifier.size(18.dp), color = FioColors.Accent, strokeWidth = 2.dp)
                LocalModel.State.Ready -> IconAction(FioIcons.Trash, "Apagar do celular", { model.delete() }, size = 40.dp)
            }
        }
        if (state is LocalModel.State.Downloading) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(FioColors.Surface2)) {
                Box(Modifier.fillMaxWidth(state.fraction.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(FioColors.Accent))
            }
        }
    }
}

/** A name and what is typed for it, on one line. */
@Composable
private fun FieldLine(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    hintColor: Color = FioColors.TextFaint,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = FioColors.Text, modifier = Modifier.width(92.dp))
        Box(Modifier.weight(1f).padding(vertical = 16.dp)) {
            if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = hintColor, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = FioColors.Text),
                cursorBrush = SolidColor(FioColors.Accent),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
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

@Composable
private fun ModelPicker(
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
            Box(Modifier.weight(1f)) { Field(value = typed, onChange = { typed = it }, hint = "Nome do modelo ou deployment") }
            AnimatedVisibility(typed.isNotBlank()) {
                IconAction(FioIcons.Check, "Usar", { onPick(typed.trim()) }, modifier = Modifier.padding(start = 8.dp), primary = true)
            }
        }
        if (!settings.apiReady) {
            Text("Preencha o endereço e a chave da API pra carregar a lista.", style = MaterialTheme.typography.bodySmall, color = FioColors.TextMuted, modifier = Modifier.padding(24.dp))
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
