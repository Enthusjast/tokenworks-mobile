package cn.edu.ustc.tokenworks

import android.app.KeyguardManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.crypto.ECDSASigner
import okhttp3.OkHttpClient
import okhttp3.CertificatePinner
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
@RunWith(AndroidJUnit4::class)
class AndroidLanCredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val account = UUID.randomUUID().toString()
    private val organization = UUID.randomUUID().toString()
    private val user = UUID.randomUUID().toString()
    private val device = UUID.randomUUID().toString()
    private val owner = LocalDataOwner(account, organization, "a".repeat(64), user, device)
    private val proofSigningKey = ECKeyGenerator(Curve.P_256).generate()
    private val proofSigner = object : DeviceProofSigner {
        override val publicKey = PublicProofKey(JsonDocument.parse(proofSigningKey.toPublicJWK().toJSONString()).root)
        override fun signJose(signingInput: ByteArray) = ECDSASigner(proofSigningKey).sign(
            com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.ES256), signingInput).decode()
    }
    private val proof get() = proofSigner.publicKey.thumbprint
    private val hostNoise by lazy { E2eSession.generateStatic() }
    private val runtime = UUID.randomUUID().toString()
    private val stores = mutableListOf<AndroidLanCredentialStore>()
    private fun store(value: LocalDataOwner = owner, signer: String = proof) = AndroidLanCredentialStore(context, value, signer).also(stores::add)
    private fun credentials() = intent().credentials
    private fun slot(value: LocalDataOwner = owner, signer: String = proof): String = "lan-" + JsonDocument.of(
        jsonObject("owner" to value.root, "proof_key_jkt" to signer.json())).sha256().take(60)

    @Before fun fixture() {
        assumeTrue("explicit secure-lock fixture required", InstrumentationRegistry.getArguments().getString("vaultFixture") == "true")
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        assertTrue(keyguard.isDeviceSecure)
        assertFalse(keyguard.isDeviceLocked)
    }
    @After fun cleanup() { stores.forEach { it.clear() } }

    @Test fun vaultSurvivesNewCoordinatorOwnerWithoutPlaintextOrExportableKeys() {
        val accepted = credentials()
        store().save(accepted)
        val encrypted = File(context.noBackupFilesDir, "account-vault/${slot()}/account.json").readText()
        assertFalse(encrypted.contains(accepted.document().string("token")))
        val reopened = store().load().single()
        assertEquals(JsonDocument.of(accepted.document()).canonicalText(), JsonDocument.of(reopened.document()).canonicalText())
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = keys.aliases().toList().single { it.startsWith("tokenworks-mobile/account-token/v1/${slot()}/") }
        assertNull(keys.getKey(alias, null).encoded)
        // Retrying an acknowledged save is idempotent and cannot replace a different device.
        store().save(accepted)
        assertEquals("lan.already_paired", assertThrows(LanPairingException::class.java) { store().save(credentials()) }.code)
        store().remove(runtime)
        assertTrue(store().load().isEmpty())
    }

    @Test fun deploymentUserDeviceAndProofOwnersCannotReadEachOthersPairing() {
        store().save(credentials())
        val variants = listOf(
            LocalDataOwner(UUID.randomUUID().toString(), organization, owner.deploymentHash, user, device),
            LocalDataOwner(account, UUID.randomUUID().toString(), owner.deploymentHash, user, device),
            LocalDataOwner(account, organization, "b".repeat(64), user, device),
            LocalDataOwner(account, organization, owner.deploymentHash, UUID.randomUUID().toString(), device),
            LocalDataOwner(account, organization, owner.deploymentHash, user, UUID.randomUUID().toString()),
        )
        for (different in variants) assertTrue(store(different).load().isEmpty())
        assertTrue(store(signer = Contract.base64url(ByteArray(32) { 4 })).load().isEmpty())
        assertEquals(1, store().load().size)
    }

    @Test fun corruptProtectedCredentialFailsClosedInsteadOfCreatingAnEmptyStore() {
        store().save(credentials())
        val file = File(context.noBackupFilesDir, "account-vault/${slot()}/account.json")
        val root = JsonDocument.parse(file.readBytes()).root
        file.writeBytes(JsonDocument.of(root.with("ciphertext" to "AAAA".json())).canonicalBytes())
        assertThrows(VaultException::class.java) { store().load() }
    }

    private fun intent(origin: String = "https://operator.example", pin: String = "sha256/" + Base64.getEncoder().encodeToString(ByteArray(32)), signer: DeviceProofSigner = proofSigner) =
        LanPairingIntent.create(LanPairingDescriptor.parse(JsonDocument.of(jsonObject("version" to 2L.json(),
            "origin" to origin.json(), "runtimeId" to runtime.json(), "installationId" to organization.json(),
            "tlsPin" to pin.json(), "code" to "51927364".json(), "expiresAt" to (System.currentTimeMillis() + 60000).json(),
            "carrierVersion" to 1L.json(), "suite" to E2eSession.SUITE.json(), "hostStaticPublicKey" to Contract.base64url(hostNoise.publicKey).json(),
            "hostKeyEpoch" to 1L.json(), "minimumEncrypted" to JsonBoolean(true))).canonicalText()), "Android", owner, signer)

    @Test fun crashBeforeSendRetainsPrivateIntentAndAtomicPromotionClearsCode() {
        val pending = intent()
        store().begin(pending)
        val encrypted = File(context.noBackupFilesDir, "account-vault/${slot()}/account.json").readText()
        assertFalse(encrypted.contains("51927364"))
        assertFalse(encrypted.contains(JsonDocument.of(pending.credentials.document()).root.string("token")))
        val reopened = store().restore()
        assertTrue(reopened.credentials.isEmpty())
        val restoredPending = reopened.pending!!
        assertEquals(pending.credentials.pairId, restoredPending.credentials.pairId)
        assertThrows(LanPairingException::class.java) { store().begin(intent()) }
        assertThrows(LanPairingException::class.java) { store().promote(credentials()) }
        assertNotNull(store().loadPending())
        store().promote(restoredPending.credentials)
        store().promote(restoredPending.credentials) // Lost local commit acknowledgment is idempotent.
        assertNull(store().loadPending())
        assertEquals(pending.credentials.pairId, store().load().single().pairId)
        val vault = AccountVault(context, slot()).read()!!
        vault.use { assertFalse(it.bytes().toString(Charsets.UTF_8).contains("51927364")) }
    }

    @Test fun pendingIntentSurvivesFailedPromotionAndExplicitForgetIsSeparate() {
        val pending = intent()
        store().begin(pending)
        val file = File(context.noBackupFilesDir, "account-vault/${slot()}/account.json")
        val directory = file.parentFile!!
        check(file.setWritable(false, false)); check(directory.setWritable(false, false))
        try { assertThrows(VaultException::class.java) { store().promote(pending.credentials) } }
        finally { check(directory.setWritable(true, true)); check(file.setWritable(true, true)) }
        assertEquals(pending.credentials.pairId, store().loadPending()!!.credentials.pairId)
        assertTrue(store().load().isEmpty())
        store().remove(runtime)
        assertNull(store().loadPending())
        assertTrue(store().load().isEmpty())
    }

    @Test fun cancelledCoordinatorRestoresHostSuccessWithoutRepeatingPairWrite() = runBlocking {
        val key = ECKeyGenerator(Curve.P_256).generate()
        val signer = object : DeviceProofSigner {
            override val publicKey = PublicProofKey(JsonDocument.parse(key.toPublicJWK().toJSONString()).root)
            override fun signJose(signingInput: ByteArray) = ECDSASigner(key).sign(
                com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.ES256), signingInput).decode()
        }
        val credentialsStore = store(signer = signer.publicKey.thumbprint)
        val ca = HeldCertificate.Builder().commonName("pairing-ca").certificateAuthority(1).build()
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").signedBy(ca).build()
        val serverKeys = HandshakeCertificates.Builder().heldCertificate(cert, ca.certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate).build()
        val factory = LanConnectionFactory.withOperatorClient(OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build())
        val server = MockWebServer()
        server.useHttps(serverKeys.sslSocketFactory(), false)
        val writes = AtomicInteger()
        val failPromotion = AtomicBoolean()
        val vaultFile = File(context.noBackupFilesDir, "account-vault/${slot(signer = signer.publicKey.thumbprint)}/account.json")
        var registered: LanCredentials? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/pair/tls" -> MockResponse().setResponseCode(204)
                "/pair" -> {
                    val body = JsonDocument.parse(request.body.readByteArray()).root
                    // The real vault must already own exactly the capability sent to the Host.
                    val durable = credentialsStore.loadPending()!!.credentials
                    assertEquals(durable.pairId, body.string("deviceId"))
                    assertEquals(JsonDocument.of(durable.document()).root.string("token"), body.string("token"))
                    registered = durable
                    writes.incrementAndGet()
                    if (failPromotion.get()) {
                        check(vaultFile.setWritable(false, false)); check(vaultFile.parentFile!!.setWritable(false, false))
                        MockResponse().setHeader("Content-Type", "application/json").setBody(JsonDocument.of(JsonObject(durable.document().fields.filterKeys { it != "deviceStaticPrivateKey" })).canonicalText())
                    } else MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
                "/pair/identity" -> if (registered == null) MockResponse().setResponseCode(401) else MockResponse()
                    .setHeader("Content-Type", "application/json").setBody(JsonDocument.of(jsonObject(
                        "runtimeId" to runtime.json(), "installationId" to organization.json(), "manifest" to registered!!.manifest,
                        "bindingSha256" to registered!!.bindingSha256.json())).canonicalText())
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val first = PairingCoordinator(credentialsStore, signer, ownerScope, factory)
        var second: PairingCoordinator? = null
        var third: PairingCoordinator? = null
        try {
            val offered = intent(server.url("/").toString().removeSuffix("/"), CertificatePinner.pin(cert.certificate), signer)
            // Simulate a process dying after the durable intent commit but before any send.
            withContext(Dispatchers.IO) { credentialsStore.begin(offered) }
            withContext(Dispatchers.Main) { first.restore() }
            withTimeout(5000) { while (first.state.value.busy) delay(10) }
            assertEquals(PairingPhase.UNKNOWN, first.state.value.phase)
            assertEquals(0, writes.get())
            assertNotNull(withContext(Dispatchers.IO) { credentialsStore.loadPending() })
            withContext(Dispatchers.Main) { first.forget(runtime) }
            withTimeout(5000) { while (first.state.value.busy) delay(10) }
            withContext(Dispatchers.Main) { first.importDescriptor(JsonDocument.of(offered.descriptor.document()).canonicalText()); first.pair("Android") }
            withTimeout(5000) { while (writes.get() == 0) delay(10) }
            withContext(Dispatchers.Main) { first.close() }
            assertNotNull(withContext(Dispatchers.IO) { credentialsStore.loadPending() })
            val restored = PairingCoordinator(credentialsStore, signer, ownerScope, factory).also { second = it }
            withContext(Dispatchers.Main) { restored.restore() }
            withTimeout(5000) { while (restored.state.value.busy) delay(10) }
            assertEquals(PairingPhase.READY, restored.state.value.phase)
            assertEquals(registered!!.pairId, restored.state.value.devices.single().pairId)
            assertNull(withContext(Dispatchers.IO) { credentialsStore.loadPending() })
            assertEquals(1, writes.get())
            // A later explicit pairing succeeds at the Host but its local vault promotion fails.
            withContext(Dispatchers.Main) { restored.forget(runtime) }
            withTimeout(5000) { while (restored.state.value.busy) delay(10) }
            failPromotion.set(true)
            withContext(Dispatchers.Main) { restored.importDescriptor(JsonDocument.of(offered.descriptor.document()).canonicalText()); restored.pair("Android") }
            withTimeout(5000) { while (restored.state.value.busy) delay(10) }
            assertEquals(PairingPhase.SAVE_REQUIRED, restored.state.value.phase)
            assertNotNull(restored.state.value.error)
            check(vaultFile.parentFile!!.setWritable(true, true)); check(vaultFile.setWritable(true, true))
            assertNotNull(withContext(Dispatchers.IO) { credentialsStore.loadPending() })
            withContext(Dispatchers.Main) { restored.close() }
            val saved = PairingCoordinator(credentialsStore, signer, ownerScope, factory).also { third = it }
            withContext(Dispatchers.Main) { saved.restore() }
            withTimeout(5000) { while (saved.state.value.busy) delay(10) }
            assertEquals(PairingPhase.READY, saved.state.value.phase)
            assertNull(withContext(Dispatchers.IO) { credentialsStore.loadPending() })
            assertEquals(2, writes.get())
        } finally {
            vaultFile.parentFile?.setWritable(true, true); vaultFile.setWritable(true, true)
            withContext(Dispatchers.Main) { first.close(); second?.close(); third?.close(); ownerScope.cancel() }
            server.shutdown()
        }
    }
}
