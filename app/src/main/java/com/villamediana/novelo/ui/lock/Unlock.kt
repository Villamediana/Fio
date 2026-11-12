package com.villamediana.novelo.ui.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The phone's own lock — fingerprint or face, or its PIN/pattern when it has no biometrics — before
 * something sensitive shows (when she turned it on in Ajustes › Privacidade).
 */
object Unlock {
    private const val WAYS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** The phone has a lock to ask for (a fingerprint, a face, or at least a PIN). */
    fun available(context: Context): Boolean =
        runCatching { BiometricManager.from(context).canAuthenticate(WAYS) == BiometricManager.BIOMETRIC_SUCCESS }.getOrDefault(false)

    /** Asks the phone's lock; true when she passed it (false: closed it, or it failed). */
    suspend fun ask(activity: FragmentActivity, title: String = t(R.string.unlock_title)): Boolean = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (cont.isActive) cont.resume(false)
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(WAYS).build()
        runCatching { prompt.authenticate(info) }.onFailure { if (cont.isActive) cont.resume(false) }
        cont.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
    }
}

/** The screen ([FragmentActivity]) this context belongs to, through any wrapper (a sheet, a dialog). */
fun Context.screen(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}
