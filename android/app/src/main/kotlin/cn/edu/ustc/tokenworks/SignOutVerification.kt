package cn.edu.ustc.tokenworks

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Local screen-lock proof authorizes only removal of the current account on this device. */
@Composable
internal fun rememberSignOutVerification(): ((Boolean) -> Unit) -> Unit {
    val context = LocalContext.current
    val activity = context.findFragmentActivity()
    val pending = remember(activity) { SignOutAttempt() }
    val credential = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pending.finish(result.resultCode == Activity.RESULT_OK)
    }
    DisposableEffect(pending) { onDispose { pending.cancel() } }
    return { complete ->
        if (activity == null || pending.complete != null) complete(false)
        else {
            pending.complete = complete
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = pending.finish(true)
                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = pending.finish(false)
                        })
                    pending.prompt = prompt
                    prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                        .setTitle(context.getString(R.string.settings_verify_exit))
                        .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                        .build())
                } else {
                    val keyguard = context.getSystemService(KeyguardManager::class.java)
                    @Suppress("DEPRECATION")
                    val request = keyguard?.createConfirmDeviceCredentialIntent(context.getString(R.string.settings_verify_exit), null)
                    if (request == null) pending.finish(false) else credential.launch(request)
                }
            } catch (_: Exception) {
                pending.finish(false)
            }
        }
    }
}

private class SignOutAttempt {
    var complete: ((Boolean) -> Unit)? = null
    var prompt: BiometricPrompt? = null

    fun finish(success: Boolean) {
        val next = complete
        complete = null
        prompt = null
        next?.invoke(success)
    }

    fun cancel() {
        val current = prompt
        complete = null
        prompt = null
        current?.cancelAuthentication()
    }
}

internal tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findFragmentActivity()
    else -> null
}
