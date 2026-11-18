package cn.edu.ustc.tokenworks

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** A local gate for native DSH approvals. This does not create a detached signature or claim MFA. */
internal suspend fun verifyStrongApproval(context: Context) = withContext(Dispatchers.Main.immediate) {
    val activity = context.approvalActivity() ?: throw DshFailure("interaction.biometric_unavailable")
    val strong = BiometricManager.Authenticators.BIOMETRIC_STRONG
    if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
        BiometricManager.from(activity).canAuthenticate(strong) != BiometricManager.BIOMETRIC_SUCCESS
    ) throw DshFailure("interaction.biometric_unavailable")
    suspendCancellableCoroutine<Unit> { continuation ->
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (!continuation.isActive) return
                if (result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC &&
                    activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) continuation.resume(Unit)
                else continuation.resumeWithException(DshFailure("interaction.biometric_cancelled"))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (continuation.isActive) continuation.resumeWithException(DshFailure("interaction.biometric_cancelled"))
            }
        })
        continuation.invokeOnCancellation {
            if (Looper.myLooper() == Looper.getMainLooper()) prompt.cancelAuthentication()
            else executor.execute { prompt.cancelAuthentication() }
        }
        try {
            if (continuation.isActive) prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.interaction_biometric_title))
                .setSubtitle(activity.getString(R.string.interaction_biometric_subtitle))
                .setNegativeButtonText(activity.getString(R.string.interaction_biometric_cancel))
                .setAllowedAuthenticators(strong)
                .build())
        } catch (_: IllegalStateException) {
            if (continuation.isActive) continuation.resumeWithException(DshFailure("interaction.biometric_unavailable"))
        } catch (_: IllegalArgumentException) {
            if (continuation.isActive) continuation.resumeWithException(DshFailure("interaction.biometric_unavailable"))
        }
    }
}

private tailrec fun Context.approvalActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.approvalActivity()
    else -> null
}
