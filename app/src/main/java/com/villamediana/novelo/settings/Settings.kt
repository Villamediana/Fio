package com.villamediana.novelo.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.villamediana.novelo.ai.Api
import com.villamediana.novelo.ai.Provider
import com.villamediana.novelo.ai.Providers

/**
 * Who hears and who thinks. Hearing: the Novelo's ears on the phone (Omni, downloaded once) or her
 * API (a transcription model). Thinking: the Novelo's own intelligence (free models online, nothing
 * to set up) or her API. The Novelo's keeps the key "local" it had when it thought on the phone
 * (until 1.19): whoever chose the Novelo stays with it.
 */
enum class Source(val key: String) {
    NOVELO("local"), API("api");

    companion object {
        fun of(key: String?): Source = entries.firstOrNull { it.key == key } ?: NOVELO
    }
}

enum class ThemeMode(val key: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        /** Light unless she chose otherwise. */
        fun of(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: LIGHT
    }
}

/** Which of her API settings: the one that thinks or the one that hears — each its own (Anthropic thinks but doesn't hear). */
enum class Job { THINK, HEAR }

/** Her API for one [Job]: whose it is, the address she typed, whether its key is saved, and the model. */
data class ApiSetup(val providerId: String, val base: String, val hasKey: Boolean, val model: String) {
    val provider: Provider get() = Providers.of(providerId)

    /** Whose it is, once she chose (an address from before, without a provider, is "Outro"). */
    val chosen: Boolean get() = providerId.isNotBlank() || base.isNotBlank()

    /** The address asked: the provider's own, or the one she typed (Foundry, another). */
    val address: String get() = provider.base ?: base

    val ready: Boolean get() = address.isNotBlank() && hasKey

    fun endpoint(key: String): Api.Endpoint = Api.Endpoint(address, key, provider.wire)
}

data class NoveloSettings(
    /** Who hears: the Novelo's ears on the phone, or her API. */
    val hear: Source = Source.NOVELO,
    /** Her API that hears (its own provider, address, key and model). */
    val hearProvider: String = "",
    val hearBase: String = "",
    val hasHearKey: Boolean = false,
    val hearModel: String = "",
    val think: Source = Source.NOVELO,
    val thinkModel: String = "",
    val apiBase: String = "",
    /** Whose API (see [Providers]); empty: set up before providers existed, the address she typed. */
    val apiProvider: String = "",
    val hasApiKey: Boolean = false,
    val theme: ThemeMode = ThemeMode.LIGHT,
    /** The highlight color's id (see NoveloAccents); empty is the thread's own vermilion. */
    val accent: String = "",
    /** The "segure ou toque" hint under the button was seen (it shows only until the first capture). */
    val hintSeen: Boolean = false,
    /** The phone's lock (fingerprint, face or its PIN) before something sensitive shows. */
    val lockSensitive: Boolean = false,
    /** The folder the backup goes to (a tree uri the app holds a permission for); empty = none. */
    val backupFolder: String = "",
    /** Where that is, to show: "Documents/Novelo backup.zip". */
    val backupPlace: String = "",
    /** When the last backup ended well (0 = never). */
    val backupAt: Long = 0,
    /** What the last backup took ("120 itens · 34 fotos"), or why it didn't go. */
    val backupNote: String = "",
    val backupFailed: Boolean = false,
    /** The first steps (permissions, backup, intelligence) were gone through. */
    val setupDone: Boolean = false,
    /** The library's tabs in her order ("today,tasks,diary…"); empty: the usual order. */
    val tabOrder: String = "",
    /** The tabs she hid ("gallery,meds"). */
    val tabsHidden: String = "",
) {
    val provider: Provider get() = Providers.of(apiProvider)

    /** The address asked: the provider's own, or the one she typed (Foundry, another). */
    val apiAddress: String get() = provider.base ?: apiBase

    val apiReady: Boolean get() = apiAddress.isNotBlank() && hasApiKey

    fun endpoint(key: String): Api.Endpoint = Api.Endpoint(apiAddress, key, provider.wire)

    /** Her API for [job]. */
    fun api(job: Job): ApiSetup = when (job) {
        Job.THINK -> ApiSetup(apiProvider, apiBase, hasApiKey, thinkModel)
        Job.HEAR -> ApiSetup(hearProvider, hearBase, hasHearKey, hearModel)
    }
}

private val Context.store: DataStore<Preferences> by preferencesDataStore("settings")

class Settings(private val context: Context, scope: CoroutineScope) {
    private val store = context.store
    private val vault = Vault()

    val flow: Flow<NoveloSettings> = store.data.map { p ->
        NoveloSettings(
            hear = Source.of(p[HEAR_BY]),
            hearProvider = p[HEAR_API_PROVIDER].orEmpty(),
            hearBase = p[HEAR_API_BASE].orEmpty(),
            hasHearKey = !p[HEAR_API_KEY].isNullOrEmpty(),
            hearModel = p[HEAR_API_MODEL].orEmpty(),
            think = Source.of(p[THINK]),
            thinkModel = p[THINK_MODEL].orEmpty(),
            apiBase = p[API_BASE].orEmpty(),
            apiProvider = p[API_PROVIDER].orEmpty(),
            hasApiKey = !p[API_KEY].isNullOrEmpty(),
            theme = ThemeMode.of(p[THEME]),
            accent = p[ACCENT].orEmpty(),
            hintSeen = p[HINT_SEEN] ?: false,
            lockSensitive = p[LOCK_SENSITIVE] ?: false,
            backupFolder = p[BACKUP_FOLDER].orEmpty(),
            backupPlace = p[BACKUP_PLACE].orEmpty(),
            backupAt = p[BACKUP_AT] ?: 0L,
            backupNote = p[BACKUP_NOTE].orEmpty(),
            backupFailed = p[BACKUP_FAILED] ?: false,
            setupDone = p[SETUP_DONE] ?: false,
            tabOrder = p[TAB_ORDER].orEmpty(),
            tabsHidden = p[TABS_HIDDEN].orEmpty(),
        )
    }

    val now: StateFlow<NoveloSettings> = flow.stateIn(scope, SharingStarted.Eagerly, NoveloSettings())

    suspend fun current(): NoveloSettings = flow.first()

    suspend fun apiKey(job: Job = Job.THINK): String? = store.data.first()[keys(job).key]?.let { vault.open(it) }

    suspend fun setHear(source: Source) = store.edit { it[HEAR_BY] = source.key }
    suspend fun setThink(source: Source) = store.edit { it[THINK] = source.key }
    suspend fun setThinkModel(model: String) = setModel(Job.THINK, model)
    suspend fun setApiBase(url: String) = setApiBase(Job.THINK, url)
    suspend fun setApiProvider(id: String) = setApiProvider(Job.THINK, id)
    suspend fun setApiKey(key: String) = setApiKey(Job.THINK, key)
    suspend fun setSetupDone() = store.edit { it[SETUP_DONE] = true }

    suspend fun setModel(job: Job, model: String) = store.edit { it[keys(job).model] = model.trim() }
    suspend fun setApiBase(job: Job, url: String) = store.edit { it[keys(job).base] = url.trim().trimEnd('/') }
    suspend fun setApiProvider(job: Job, id: String) = store.edit { it[keys(job).provider] = id }
    suspend fun setApiKey(job: Job, key: String) = store.edit { p ->
        val k = key.trim()
        if (k.isEmpty()) p.remove(keys(job).key) else p[keys(job).key] = vault.seal(k)
    }

    /** The other job's key and address for this one (she tapped "usar a mesma chave"): the same provider, the same account. */
    suspend fun shareKey(from: Job, to: Job) = store.edit { p ->
        val f = keys(from)
        val t = keys(to)
        p[f.key]?.let { p[t.key] = it }
        p[f.base]?.let { p[t.base] = it }
    }

    /** Where each job's API lives in the store. */
    private class Keys(
        val provider: Preferences.Key<String>,
        val base: Preferences.Key<String>,
        val key: Preferences.Key<String>,
        val model: Preferences.Key<String>,
    )

    private fun keys(job: Job): Keys = when (job) {
        Job.THINK -> Keys(API_PROVIDER, API_BASE, API_KEY, THINK_MODEL)
        Job.HEAR -> Keys(HEAR_API_PROVIDER, HEAR_API_BASE, HEAR_API_KEY, HEAR_API_MODEL)
    }
    suspend fun setTheme(mode: ThemeMode) = store.edit { it[THEME] = mode.key }
    suspend fun setAccent(id: String) = store.edit { it[ACCENT] = id }
    suspend fun setHintSeen() = store.edit { it[HINT_SEEN] = true }
    suspend fun setLockSensitive(on: Boolean) = store.edit { it[LOCK_SENSITIVE] = on }

    /** The library's tabs: their order and the ones hidden (keys as [com.villamediana.novelo.ui.library.TabOrder] writes them). */
    suspend fun setTabs(order: List<String>, hidden: Set<String>) = store.edit {
        it[TAB_ORDER] = order.joinToString(",")
        it[TABS_HIDDEN] = hidden.joinToString(",")
    }
    suspend fun setBackupPlace(place: String) = store.edit { it[BACKUP_PLACE] = place }

    suspend fun setBackupFolder(uri: String, place: String) = store.edit {
        it[BACKUP_FOLDER] = uri
        it[BACKUP_PLACE] = place
        it.remove(BACKUP_AT)
        it.remove(BACKUP_NOTE)
        it.remove(BACKUP_FAILED)
    }
    suspend fun setBackupDone(at: Long, note: String) = store.edit {
        it[BACKUP_AT] = at
        it[BACKUP_NOTE] = note
        it[BACKUP_FAILED] = false
    }
    suspend fun setBackupFailed(note: String) = store.edit {
        it[BACKUP_NOTE] = note
        it[BACKUP_FAILED] = true
    }

    private companion object {
        /** Who hears (1.55). Not the old "hear" of the Fio, which may still say "api" from back then. */
        val HEAR_BY = stringPreferencesKey("hear_by")
        val HEAR_API_PROVIDER = stringPreferencesKey("hear_api_provider")
        val HEAR_API_BASE = stringPreferencesKey("hear_api_base")
        val HEAR_API_KEY = stringPreferencesKey("hear_api_key_sealed")
        val HEAR_API_MODEL = stringPreferencesKey("hear_api_model")
        val THINK = stringPreferencesKey("think")
        val THINK_MODEL = stringPreferencesKey("think_model")
        val API_BASE = stringPreferencesKey("api_base")
        val API_KEY = stringPreferencesKey("api_key_sealed")
        val API_PROVIDER = stringPreferencesKey("api_provider")
        val SETUP_DONE = booleanPreferencesKey("setup_done")
        val THEME = stringPreferencesKey("theme")
        val ACCENT = stringPreferencesKey("accent")
        val HINT_SEEN = booleanPreferencesKey("hint_seen")
        val LOCK_SENSITIVE = booleanPreferencesKey("lock_sensitive")
        val TAB_ORDER = stringPreferencesKey("tab_order")
        val TABS_HIDDEN = stringPreferencesKey("tabs_hidden")
        val BACKUP_FOLDER = stringPreferencesKey("backup_folder")
        val BACKUP_PLACE = stringPreferencesKey("backup_place")
        val BACKUP_AT = longPreferencesKey("backup_at")
        val BACKUP_NOTE = stringPreferencesKey("backup_note")
        val BACKUP_FAILED = booleanPreferencesKey("backup_failed")
    }
}

/** The API key never sits in plain text on disk: AES-GCM with a key that lives in the AndroidKeyStore. */
private class Vault {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun seal(plain: String): String = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        "k1:" + Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(out, Base64.NO_WRAP)
    }.getOrElse { "p1:" + Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) } // no keystore (tests)

    fun open(sealed: String): String? = runCatching {
        val parts = sealed.split(':')
        when (parts.firstOrNull()) {
            "k1" -> {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
                String(c.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), Charsets.UTF_8)
            }
            "p1" -> String(Base64.decode(parts[1], Base64.NO_WRAP), Charsets.UTF_8)
            else -> null
        }
    }.getOrNull()

    private companion object {
        const val ALIAS = "novelo-api-key"
    }
}
