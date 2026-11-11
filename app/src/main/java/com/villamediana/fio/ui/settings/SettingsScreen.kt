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
import com.villamediana.fio.BuildConfig
import com.villamediana.fio.FioApplication
import com.villamediana.fio.ai.Api
import com.villamediana.fio.local.LocalEngine
import com.villamediana.fio.local.LocalModel
import com.villamediana.fio.settings.FioSettings
import com.villamediana.fio.settings.Source
import com.villamediana.fio.settings.SpeechLang
import com.villamediana.fio.settings.ThemeMode
import com.villamediana.fio.ui.Nav
import com.villamediana.fio.ui.Screen
import com.villamediana.fio.ui.components.FioIcons
import com.villamediana.fio.ui.components.FioMark
import com.villamediana.fio.ui.components.Group
import com.villamediana.fio.ui.components.Pill
import com.villamediana.fio.ui.components.SectionLabel
import com.villamediana.fio.ui.components.Segmented
import com.villamediana.fio.ui.components.formatBytes
import com.villamediana.fio.ui.home.QuietIcon
import com.villamediana.fio.ui.sheets.FioSheet
import com.villamediana.fio.ui.theme.FioAccents
import com.villamediana.fio.ui.theme.FioColors
import com.villamediana.fio.ui.update.UpdateRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/** What the API check said: a few words, and whether it went well. */
private data class Note(val text: String, val ok: Boolean? = null)

@Composable
fun SettingsScreen(nav: Nav) {
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

    LaunchedEffect(s.apiBase) { if (base.isEmpty()) base = s.apiBase }
    // Typed is kept: the address a beat after typing, the key when leaving the screen.
    LaunchedEffect(base) { delay(500); if (base.trim().trimEnd('/') != s.apiBase) st.setApiBase(base) }
    val pendingKey by rememberUpdatedState(key)
    DisposableEffect(Unit) {
        onDispose { if (pendingKey.isNotBlank()) graph.scope.launch { st.setApiKey(pendingKey); graph.mind.retryPending() } }
    }

    fun loadModels() {
        scope.launch {
            loading = true
            note = null
            if (base.isNotBlank()) st.setApiBase(base)
            if (key.isNotBlank()) { st.setApiKey(key); key = "" }
            val k = st.apiKey()
            val b = st.current().apiBase
            if (b.isBlank() || k.isNullOrBlank()) {
                note = Note("falta o endereço ou a chave", ok = false)
                loading = false
                return@launch
            }
            runCatching { withContext(Dispatchers.IO) { graph.api.models(Api.Endpoint(b, k)) } }
                .onSuccess { models = it; note = if (it.isEmpty()) Note("conectou, sem lista de modelos", ok = true) else Note("conectou · ${it.size} modelos", ok = true) }
                .onFailure { note = Note("não conectou · ${it.message}", ok = false) }
            loading = false
            graph.mind.retryPending()
        }
    }

    Column(Modifier.fillMaxSize().background(FioColors.Background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietIcon(FioIcons.Back, "Voltar", { nav.back() }, alpha = 0.8f)
            Text("Ajustes", style = MaterialTheme.typography.headlineMedium, color = FioColors.Text, modifier = Modifier.padding(start = 6.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // Who hears and who thinks: the phone or the API.
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

            SectionLabel("No celular")
            Group(Modifier, { LocalModelLine(graph.localModel, local) })

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
                {
                    Line(
                        title = "Conexão",
                        subtitle = when {
                            loading -> "testando…"
                            note != null -> note!!.text
                            s.apiReady -> null
                            else -> "falta o endereço ou a chave"
                        },
                        subtitleColor = when (note?.ok) { true -> FioColors.Success; false -> FioColors.Danger; null -> FioColors.TextMuted },
                        onClick = { if (!loading) loadModels() },
                    ) {
                        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = FioColors.Accent, strokeWidth = 2.dp)
                        else Pill("Testar", selected = true, onClick = { loadModels() })
                    }
                },
            )

            SectionLabel("Preferências")
            Group(
                Modifier,
                {
                    Line(title = "Idioma da fala") {
                        Segmented(
                            listOf("Auto", "PT", "ES", "EN"),
                            selected = SpeechLang.entries.indexOf(s.lang),
                            onSelect = { i -> scope.launch { st.setLang(SpeechLang.entries[i]) } },
                        )
                    }
                },
                {
                    // Light, dark and the thread's color live on their own page, with a preview.
                    Line(title = "Tema", onClick = { nav.go(Screen.Theme) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (s.theme) { ThemeMode.SYSTEM -> "Sistema"; ThemeMode.LIGHT -> "Claro"; ThemeMode.DARK -> "Escuro" } + " · " + FioAccents.byId(s.accent).name.lowercase(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = FioColors.TextMuted,
                            )
                            Icon(FioIcons.Chevron, null, tint = FioColors.TextFaint, modifier = Modifier.padding(start = 4.dp).size(18.dp))
                        }
                    }
                },
            )

            SectionLabel("Sobre")
            val updateLine: (@Composable () -> Unit)? = if (graph.updates.enabled) ({ UpdateRow() }) else null
            Group(
                Modifier,
                {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        FioMark(34.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Fio", style = MaterialTheme.typography.titleMedium, color = FioColors.Text)
                            Text("versão ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = FioColors.TextMuted)
                        }
                    }
                },
                updateLine,
            )
            Spacer(Modifier.height(40.dp))
        }
    }

    picking?.let { job ->
        ModelPicker(
            title = if (job == Pick.HEAR) "Modelo que transcreve" else "Modelo que organiza",
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

private enum class Pick { HEAR, THINK }

/** The model on the phone in a few words: what it needs or how it is. */
private fun localShort(state: LocalModel.State): String = when {
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
                LocalModel.State.Missing, is LocalModel.State.Failed -> Pill("Baixar", selected = true, onClick = { model.download() })
                is LocalModel.State.Downloading -> Pill("Parar", selected = false, onClick = { model.cancel() })
                LocalModel.State.Checking -> CircularProgressIndicator(Modifier.size(18.dp), color = FioColors.Accent, strokeWidth = 2.dp)
                LocalModel.State.Ready -> Pill("Apagar", selected = false, onClick = { model.delete() })
            }
        }
        if (state is LocalModel.State.Downloading) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(FioColors.Surface2)) {
                Box(Modifier.fillMaxWidth(state.fraction.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(FioColors.Accent))
            }
        }
    }
}

/** A row of a block: the name (and a word under it) on the left, the control on the right. */
@Composable
private fun Line(
    title: String,
    subtitle: String? = null,
    subtitleColor: Color = FioColors.TextMuted,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = FioColors.Text)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        trailing()
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
                Pill("Usar", selected = true, onClick = { onPick(typed.trim()) }, modifier = Modifier.padding(start = 8.dp))
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
