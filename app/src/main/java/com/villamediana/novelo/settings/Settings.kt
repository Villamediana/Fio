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

/** Where hearing (speech → text) or thinking (organizing) happens. */
/**
 * Who thinks: the Novelo's own intelligence (free models online, nothing to set up) or hers (her
 * API). Hearing is always on the phone. The Novelo's keeps the key "local" it had when it thought
 * on the phone (until 1.19): whoever chose the Novelo stays with it.
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
        fun of(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

data class NoveloSettings(
    val hear: Source = Source.NOVELO,
    val hearModel: String = "",
    val think: Source = Source.NOVELO,
    val thinkModel: String = "",
    val apiBase: String = "",
    /** Whose API (see [Providers]); empty: set up before providers existed, the address she typed. */
    val apiProvider: String = "",
    val hasApiKey: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** The highlight color's id (see NoveloAccents); empty is the thread's own vermilion. */
    val accent: String = "",
    /** The "segure ou toque" hint under the button was seen (it shows only until the first capture). */
    val hintSeen: Boolean = false,
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
) {
    val provider: Provider get() = Providers.of(apiProvider)

    /** The address asked: the provider's own, or the one she typed (Foundry, another). */
    val apiAddress: String get() = provider.base ?: apiBase

    val apiReady: Boolean get() = apiAddress.isNotBlank() && hasApiKey

    fun endpoint(key: String): Api.Endpoint = Api.Endpoint(apiAddress, key, provider.wire)
}

private val Context.store: DataStore<Preferences> by preferencesDataStore("settings")

class Settings(private val context: Context, scope: CoroutineScope) {
    private val store = context.store
    private val vault = Vault()

    val flow: Flow<NoveloSettings> = store.data.map { p ->
        NoveloSettings(
            hear = Source.of(p[HEAR]),
            hearModel = p[HEAR_MODEL].orEmpty(),
            think = Source.of(p[THINK]),
            thinkModel = p[THINK_MODEL].orEmpty(),
            apiBase = p[API_BASE].orEmpty(),
            apiProvider = p[API_PROVIDER].orEmpty(),
            hasApiKey = !p[API_KEY].isNullOrEmpty(),
            theme = ThemeMode.of(p[THEME]),
            accent = p[ACCENT].orEmpty(),
            hintSeen = p[HINT_SEEN] ?: false,
            backupFolder = p[BACKUP_FOLDER].orEmpty(),
            backupPlace = p[BACKUP_PLACE].orEmpty(),
            backupAt = p[BACKUP_AT] ?: 0L,
            backupNote = p[BACKUP_NOTE].orEmpty(),
            backupFailed = p[BACKUP_FAILED] ?: false,
            setupDone = p[SETUP_DONE] ?: false,
        )
    }

    val now: StateFlow<NoveloSettings> = flow.stateIn(scope, SharingStarted.Eagerly, NoveloSettings())

    suspend fun current(): NoveloSettings = flow.first()

    suspend fun apiKey(): String? = store.data.first()[API_KEY]?.let { vault.open(it) }

    suspend fun setHear(source: Source) = store.edit { it[HEAR] = source.key }
    suspend fun setHearModel(model: String) = store.edit { it[HEAR_MODEL] = model.trim() }
    suspend fun setThink(source: Source) = store.edit { it[THINK] = source.key }
    suspend fun setThinkModel(model: String) = store.edit { it[THINK_MODEL] = model.trim() }
    suspend fun setApiBase(url: String) = store.edit { it[API_BASE] = url.trim().trimEnd('/') }
    suspend fun setApiProvider(id: String) = store.edit { it[API_PROVIDER] = id }
    suspend fun setSetupDone() = store.edit { it[SETUP_DONE] = true }
    suspend fun setApiKey(key: String) = store.edit { p ->
        val k = key.trim()
        if (k.isEmpty()) p.remove(API_KEY) else p[API_KEY] = vault.seal(k)
    }
    suspend fun setTheme(mode: ThemeMode) = store.edit { it[THEME] = mode.key }
    suspend fun setAccent(id: String) = store.edit { it[ACCENT] = id }
    suspend fun setHintSeen() = store.edit { it[HINT_SEEN] = true }
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
        val HEAR = stringPreferencesKey("hear")
        val HEAR_MODEL = stringPreferencesKey("hear_model")
        val THINK = stringPreferencesKey("think")
        val THINK_MODEL = stringPreferencesKey("think_model")
        val API_BASE = stringPreferencesKey("api_base")
        val API_KEY = stringPreferencesKey("api_key_sealed")
        val API_PROVIDER = stringPreferencesKey("api_provider")
        val SETUP_DONE = booleanPreferencesKey("setup_done")
        val THEME = stringPreferencesKey("theme")
        val ACCENT = stringPreferencesKey("accent")
        val HINT_SEEN = booleanPreferencesKey("hint_seen")
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
