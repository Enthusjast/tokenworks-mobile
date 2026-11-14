package cn.edu.ustc.tokenworks

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** Scoped display history, excluded from Android backups. Core confines even root/key lookup to IO. */
internal class EncryptedTranscriptCache(context: Context) {
    private val application = context.applicationContext
    private val storage = EncryptedTranscriptStore(
        { File(application.noBackupFilesDir, "dsh-transcripts") }, TranscriptKeyProvider(::key))

    fun forRoute(route: DshCommandRoute): DshTranscriptCache = storage.forRoute(route)
    suspend fun clearOwner(ownerId: String) = storage.clearOwner(ownerId)
    suspend fun clearHost(ownerId: String, hostId: String) = storage.clearHost(ownerId, hostId)
    suspend fun clearRuntime(ownerId: String, hostId: String, runtimeId: String) = storage.clearRuntime(ownerId, hostId, runtimeId)
    suspend fun clearAll() = storage.clearAll()

    private fun key(create: Boolean): SecretKey {
        val keyguard = application.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure || keyguard.isDeviceLocked) throw java.io.IOException("cache key unavailable")
        val alias = "tokenworks-mobile/dsh-transcript/v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            if (!create) throw java.security.GeneralSecurityException("cache key unavailable")
            val specification = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
            if (Build.VERSION.SDK_INT >= 35) specification.setUnlockedDeviceRequired(true)
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(specification.build()) }.generateKey()
        }
        val key = store.getKey(alias, null) as? SecretKey ?: throw java.security.GeneralSecurityException("cache key unavailable")
        val info = SecretKeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (key.encoded != null || info.keySize != 256 || info.origin != KeyProperties.ORIGIN_GENERATED ||
            info.purposes != (KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT) ||
            info.blockModes.toSet() != setOf(KeyProperties.BLOCK_MODE_GCM) ||
            info.encryptionPaddings.toSet() != setOf(KeyProperties.ENCRYPTION_PADDING_NONE) || info.isUserAuthenticationRequired) {
            throw java.security.GeneralSecurityException("cache key policy")
        }
        return key
    }
}
