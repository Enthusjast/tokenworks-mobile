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

/** Android-only key custody. All key and file operations run inside the core outbox IO boundary. */
internal class EncryptedOutboxStore(context: Context) {
    private val application = context.applicationContext
    private val file = File(application.noBackupFilesDir, "dsh-outbox/commands.bin")

    suspend fun open(): DshCommandOutbox = DshCommandOutbox.open(EncryptedFileOutboxStore(file, OutboxKeyProvider(::key)))

    private fun key(create: Boolean): SecretKey {
        val keyguard = application.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure || keyguard.isDeviceLocked) throw OutboxException(OutboxFailure.UNAVAILABLE)
        val alias = "tokenworks-mobile/dsh-command-outbox/v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            if (!create) throw OutboxException(OutboxFailure.UNAVAILABLE)
            val specification = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
            // Older platform versions have unlocked-device key-loss bugs; the explicit gate above still applies.
            if (Build.VERSION.SDK_INT >= 35) specification.setUnlockedDeviceRequired(true)
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(specification.build()) }.generateKey()
        }
        val key = store.getKey(alias, null) as? SecretKey ?: throw OutboxException(OutboxFailure.UNAVAILABLE)
        val info = SecretKeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (key.encoded != null || info.keySize != 256 || info.origin != KeyProperties.ORIGIN_GENERATED ||
            info.purposes != (KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT) ||
            info.blockModes.toSet() != setOf(KeyProperties.BLOCK_MODE_GCM) ||
            info.encryptionPaddings.toSet() != setOf(KeyProperties.ENCRYPTION_PADDING_NONE) || info.isUserAuthenticationRequired) {
            throw OutboxException(OutboxFailure.CORRUPT)
        }
        return key
    }
}
