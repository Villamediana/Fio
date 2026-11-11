package com.villamediana.fio.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import com.villamediana.fio.ui.components.FioIcons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import com.villamediana.fio.ui.components.FioMark
import com.villamediana.fio.ui.components.Pill
import com.villamediana.fio.ui.components.SectionLabel
import com.villamediana.fio.ui.components.formatBytes
import com.villamediana.fio.ui.home.QuietIcon
import com.villamediana.fio.ui.sheets.FioSheet
import com.villamediana.fio.ui.theme.FioColors
import com.villamediana.fio.ui.update.UpdateRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

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
    var apiNote by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Source?>(null) } // which model list is open: hear (LOCAL key reused) / think
    var pickFor by remember { mutableStateOf("") }

    LaunchedEffect(s.apiBase) { if (base.isEmpty()) base = s.apiBase }
    // Typed is kept: the address a beat after typing, the key when leaving the screen.
    LaunchedEffect(base) { kotlinx.coroutines.delay(500); if (base.trim().trimEnd('/') != s.apiBase) st.setApiBase(base) }
    val pendingKey by androidx.compose.runtime.rememberUpdatedState(key)
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { if (pendingKey.isNotBlank()) graph.scope.launch { st.setApiKey(pendingKey); graph.mind.retryPending() } }
    }

    fun loadModels() {
        scope.launch {
            loading = true
            apiNote = null
            if (base.isNotBlank()) st.setApiBase(base)
            if (key.isNotBlank()) { st.setApiKey(key); key = "" }
            val k = st.apiKey()
            val b = st.current().apiBase
            if (b.isBlank() || k.isNullOrBlank()) {
                apiNote = "Falta o endereço ou a chave."
                loading = false
                return@launch
            }
            runCatching { withContext(Dispatchers.IO) { graph.api.models(Api.Endpoint(b, k)) } }
                .onSuccess { models = it; apiNote = if (it.isEmpty()) "Conectou, mas não listou modelos. Digite o nome abaixo." else "${it.size} modelos." }
                .onFailure { apiNote = "Não conectou: ${it.message}" }
            loading = false
            graph.mind.retryPending()
        }
    }

    Column(Modifier.fillMaxSize().background(FioColors.Background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietIcon(FioIcons.Back, "Voltar", { nav.back() }, alpha = 0.8f)
            Text("Ajustes", style = MaterialTheme.typography.headlineMedium, color = FioColors.Text, modifier = Modifier.padding(start = 6.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            // ------------------------------------------------ hearing
            SectionLabel("Ouvir · transcrever a fala")
            Choice(
                selected = s.hear == Source.LOCAL,
                title = "No celular · ${LocalModel.NAME}",
                subtitle = localSubtitle(local, graph.localModel.totalBytes),
                onClick = { scope.launch { st.setHear(Source.LOCAL) } },
            )
            Choice(
                selected = s.hear == Source.API,
                title = "Pela API",
                subtitle = s.hearModel.ifBlank { "escolha o modelo (ex.: whisper, gpt-4o-transcribe)" },
                onClick = { scope.launch { st.setHear(Source.API) }; pickFor = "hear"; picking = Source.API },
            )
            if (s.hear == Source.LOCAL) LocalModelRow(local)

            // ------------------------------------------------ thinking
            SectionLabel("Pensar · organizar e responder")
            Choice(
                selected = s.think == Source.LOCAL,
                title = "No celular · ${LocalModel.NAME}",
                subtitle = "o mesmo modelo; mais lento e mais simples",
                onClick = { scope.launch { st.setThink(Source.LOCAL) } },
            )
            Choice(
                selected = s.think == Source.API,
                title = "Pela API",
                subtitle = s.thinkModel.ifBlank { "escolha o modelo" },
                onClick = { scope.launch { st.setThink(Source.API) }; pickFor = "think"; picking = Source.API },
            )

            // ------------------------------------------------ API
            SectionLabel("API")
            Field(value = base, onChange = { base = it }, hint = "Endereço · https://…/v1  (OpenAI, Foundry, OpenRouter…)", keyboard = KeyboardType.Uri)
            Spacer(Modifier.height(8.dp))
            Field(value = key, onChange = { key = it }, hint = if (s.hasApiKey) "Chave guardada · digite pra trocar" else "Chave da API", secret = true)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(if (loading) "Carregando…" else "Carregar modelos", selected = true, onClick = { if (!loading) loadModels() })
                Spacer(Modifier.width(12.dp))
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = FioColors.Accent, strokeWidth = 2.dp)
                apiNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = FioColors.TextMuted, modifier = Modifier.weight(1f)) }
            }
            Text(
                "A chave fica cifrada no aparelho. O que você fala vai pra essa API quando ela é escolhida acima.",
                style = MaterialTheme.typography.bodySmall,
                color = FioColors.TextFaint,
                modifier = Modifier.padding(top = 10.dp),
            )

            // ------------------------------------------------ language
            SectionLabel("Idioma da fala")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeechLang.entries.forEach { l ->
                    Pill(
                        when (l) { SpeechLang.AUTO -> "Automático"; SpeechLang.PT -> "Português"; SpeechLang.ES -> "Español"; SpeechLang.EN -> "English" },
                        selected = s.lang == l,
                        onClick = { scope.launch { st.setLang(l) } },
                    )
                }
            }

            // ------------------------------------------------ look
            SectionLabel("Aparência")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m ->
                    Pill(when (m) { ThemeMode.SYSTEM -> "Sistema"; ThemeMode.LIGHT -> "Claro"; ThemeMode.DARK -> "Escuro" }, selected = s.theme == m, onClick = { scope.launch { st.setTheme(m) } })
                }
            }

            // ------------------------------------------------ about
            SectionLabel("Sobre")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                FioMark(36.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Fio", style = MaterialTheme.typography.titleMedium, color = FioColors.Text)
                    Text("Versão ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = FioColors.TextMuted)
                }
            }
            Box(Modifier.clip(RoundedCornerShape(16.dp))) { UpdateRow() }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (picking != null) {
        ModelPicker(
            title = if (pickFor == "hear") "Modelo que transcreve" else "Modelo que organiza",
            current = if (pickFor == "hear") s.hearModel else s.thinkModel,
            models = models,
            settings = s,
            onLoad = { loadModels() },
            onPick = { m ->
                scope.launch {
                    if (pickFor == "hear") st.setHearModel(m) else st.setThinkModel(m)
                    graph.mind.retryPending()
                }
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

private fun localSubtitle(state: LocalModel.State, total: Long): String = when {
    !LocalEngine.available -> "ainda não vem nesta versão"
    state is LocalModel.State.Ready -> "pronto · funciona sem internet"
    state is LocalModel.State.Downloading -> "baixando · " + NumberFormat.getPercentInstance().format(state.fraction.toDouble())
    state is LocalModel.State.Checking -> "conferindo o arquivo…"
    state is LocalModel.State.Failed -> state.message
    else -> "baixar ${formatBytes(total)} · funciona sem internet"
}

@Composable
private fun LocalModelRow(state: LocalModel.State) {
    if (!LocalEngine.available) return
    val model = FioApplication.graph.localModel
    Row(Modifier.padding(start = 40.dp, top = 2.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            LocalModel.State.Missing, is LocalModel.State.Failed -> Pill("Baixar ${formatBytes(model.totalBytes)}", selected = true, onClick = { model.download() })
            is LocalModel.State.Downloading -> {
                Box(Modifier.width(160.dp).height(6.dp).clip(CircleShape).background(FioColors.Surface2).align(Alignment.CenterVertically)) {
                    Box(Modifier.fillMaxWidth(state.fraction).height(6.dp).clip(CircleShape).background(FioColors.Accent))
                }
                Pill("Parar", selected = false, onClick = { model.cancel() })
            }
            LocalModel.State.Checking -> Unit
            LocalModel.State.Ready -> Pill("Apagar do celular", selected = false, onClick = { model.delete() })
        }
    }
}

@Composable
private fun Choice(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp).animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(if (selected) FioColors.Accent else FioColors.Background)
                .border(1.6.dp, if (selected) FioColors.Accent else FioColors.TextFaint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(FioIcons.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = FioColors.Text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = FioColors.TextMuted)
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(FioColors.Surface2).padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = FioColors.TextFaint)
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
