package cn.edu.ustc.tokenworks

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Opt-in real installed-native proof, not part of ordinary connectedCheck.
 * adb run-as injects one private fixture file; no production account/profile is read.
 * Picker input and the SAF transaction destination are injected at the production
 * FilesCoordinator seams. Terminal, composer, preview and approval/question cards
 * are the real production composables, driven through Android accessibility.
 */
@RunWith(AndroidJUnit4::class)
class NativeHostScenarioTest {
    @Test fun b10b11b12InstalledNativeHostConsumerJourney() = runBlocking<Unit> {
        val argument = InstrumentationRegistry.getArguments().getString("nativeHostFixture")
        assumeTrue("explicit private native Host fixture required", argument != null)
        require(argument == "native-host-fixture.json")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val descriptorFile = File(context.filesDir, argument)
        assertEquals("The opt-in native Host fixture requires an enrolled strong test biometric",
            BiometricManager.BIOMETRIC_SUCCESS,
            BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG))
        require(descriptorFile.isFile && descriptorFile.length() in 1..65536)
        val fixture = JsonDocument.parse(descriptorFile.readBytes()).root
        // Erase the one-time code copy before creating any UI or runtime transport.
        check(descriptorFile.delete())
        assertEquals(1L, fixture.integer("version", 1, 1))
        assertEquals("0.2.1-rc.5", fixture.string("dshVersion"))
        val descriptor = LanPairingDescriptor.parse(JsonDocument.of(fixture.obj("pairing")).canonicalText())
        require(descriptor.origin.startsWith("https://localhost:"))
        val ca = CertificateFactory.getInstance("X.509").generateCertificate(fixture.string("operatorCaPem").byteInputStream()) as X509Certificate
        ca.checkValidity(); require(ca.basicConstraints >= 0)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(ca).build()
        // No custom hostname verifier, trust-all manager or pin bypass.
        val http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        val keys = ECKeyGenerator(Curve.P_256).generate()
        val signer = object : DeviceProofSigner {
            override val publicKey = PublicProofKey(JsonDocument.parse(keys.toPublicJWK().toJSONString()).root)
            override fun signJose(signingInput: ByteArray): ByteArray = ECDSASigner(keys)
                .sign(JWSHeader(JWSAlgorithm.ES256), signingInput).decode()
        }
        val factory = LanConnectionFactory.withOperatorClient(http)
        val credentials = factory.pair(signer, LanPairingIntent.create(descriptor, "Android isolated native proof", fixtureOwner(fixture), signer))
        val transport = factory.transport(credentials, signer)
        val failure = AtomicReference<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> failure.compareAndSet(null, error) })
        val sessionId = fixture.string("sessionId")
        val directory = File(context.cacheDir, "native-host-${UUID.randomUUID()}").apply { mkdirs() }
        val terminal = TerminalWatcher(transport, sessionId, scope)
        val files = FilesCoordinator(transport, File(directory, "staging"), scope)
        val interactions = InteractionCoordinator(scope)
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        assertTrue("native encrypted cache proof requires the secured test device", keyguard.isDeviceSecure)
        assertFalse(keyguard.isDeviceLocked)
        val cacheManager = EncryptedTranscriptCache(context)
        val cacheRoute = DshCommandRoute(fixtureOwner(fixture).fingerprint, descriptor.installationId, descriptor.runtimeId, sessionId)
        val cache = cacheManager.forRoute(cacheRoute)
        val reader = DshSessionReader(transport, sessionId, scope, maxMessages = 100, cache = cache)
        try {
            transport.probeIdentity()
            assertEquals(descriptor.runtimeId, transport.runtimeId)
            assertEquals(descriptor.installationId, transport.installationId)
            assertTrue(DshSessionClient(transport).sessions().items.any { it.id == sessionId })
            val opening = withTimeoutOrNull(20_000) { reader.state.first { it.phase in setOf(
                SessionReadPhase.READY, SessionReadPhase.ERROR, SessionReadPhase.AUTH_REQUIRED, SessionReadPhase.REVOKED, SessionReadPhase.CLOSED) } }
            check(opening?.phase == SessionReadPhase.READY) {
                "Native session follow did not become ready: ${reader.state.value.phase}/${reader.state.value.error}; route=${transport.state.value}"
            }
            NativeChatFixture.launch(WorkspaceViewport(800, 1400), activityClass = ApprovalUiFixtureActivity::class.java).use { ui ->
                terminalJourney(ui, terminal, failure)
                fileJourney(ui, fixture, transport, files, reader, directory, scope, failure)
                interactionJourney(ui, fixture, transport, reader, interactions, scope, failure)
                interactions.stop()
            }
            NativeChatFixture.launch(WorkspaceViewport(800, 1400)).use { ui ->
                cacheRestartJourney(ui, factory, credentials, signer, reader, cache, cacheRoute, scope, failure)
            }
            // Explicit device revocation uses the admitted production transport too.
            transport.revoke()
            assertEquals(DshTransportPhase.CLOSED, transport.state.value.phase)
            val revoked = factory.transport(credentials, signer)
            try {
                val error = runCatching { revoked.probeIdentity() }.exceptionOrNull()
                assertNotNull("Revoked pairing must reject a fresh native carrier", error)
                assertTrue(revoked.state.value.phase in setOf(DshTransportPhase.AUTH_REQUIRED, DshTransportPhase.REVOKED))
            } finally { revoked.close() }
        } catch (error: Throwable) {
            val read = reader.state.value
            throw AssertionError("Native consumer failed: reader=${read.phase}, error=${read.error}, stale=${read.stale}, route=${transport.state.value}", error)
        } finally {
            interactions.close(); terminal.close(); files.close(); reader.close()
            transport.close(); scope.cancel(); directory.deleteRecursively()
            withContext(NonCancellable + Dispatchers.IO) { cache.clear() }
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
        }
        failure.get()?.let { throw AssertionError("Native scenario action failed", it) }
    }

    private suspend fun terminalJourney(ui: NativeChatFixture, terminal: TerminalWatcher, failure: AtomicReference<Throwable?>) {
        fun label(id: Int) = ui.activity.getString(id)
        ui.content { TerminalScreen(terminal) }
        ui.click(label(R.string.terminal_new))
        eventually(failure, "terminal writable snapshot") { terminal.state.value.writable }
        val id = terminal.state.value.attached!!.id
        assertEquals(1, terminal.state.value.terminals.size)
        ui.act(AccessibilityNodeInfo.ACTION_SET_TEXT, { it.editable }, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "printf 'B10 原样🙂\\n'")
        })
        ui.click(label(R.string.terminal_send))
        eventually(failure, "Unicode PTY output line") { terminal.state.value.screen.lineSequence().any { it.trim() == "B10 原样🙂" } }
        ui.await("Unicode output rendered from native PTY") { nodes -> nodes.any { it.contains("B10 原样🙂") } }
        ui.click(label(R.string.terminal_search))
        ui.setText("terminal-output-search", "原样🙂")
        val firstMatch = ui.activity.getString(R.string.terminal_match_count, 1, 2)
        ui.await("search finds echoed command and rendered output") { nodes -> nodes.any { it.label(firstMatch) } }
        ui.click(label(R.string.terminal_next_match))
        ui.await("search navigates to the next output match") { nodes ->
            nodes.any { it.label(ui.activity.getString(R.string.terminal_match_count, 2, 2)) }
        }
        ui.click(label(R.string.terminal_search))
        ui.click("100×32")
        eventually(failure, "native resize metadata") { terminal.state.value.attached?.cols == 100 && terminal.state.value.attached?.rows == 32 }
        ui.act(AccessibilityNodeInfo.ACTION_SET_TEXT, { it.editable }, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "stty size")
        })
        ui.click(label(R.string.terminal_send))
        eventually(failure, "PTY stty size output") { terminal.state.value.screen.lineSequence().any { it.trim() == "32 100" } }
        ui.click(label(R.string.terminal_detach))
        eventually(failure, "local detach") { terminal.state.value.attached == null }
        assertEquals(id, terminal.state.value.terminals.single().id)
        ui.click(label(R.string.terminal_connect))
        eventually(failure, "same PTY reattachment") { terminal.state.value.writable && terminal.state.value.attached?.id == id }
        assertTrue(terminal.state.value.screen.contains("B10 原样🙂"))
        ui.click(label(R.string.terminal_close_process))
        ui.await("destructive process close confirmation") { nodes -> nodes.any { it.label(label(R.string.terminal_close_title)) } }
        ui.click(label(R.string.terminal_close_process))
        eventually(failure, "native process closed") { terminal.state.value.terminals.isEmpty() && terminal.state.value.attached == null }
        ui.await("closed terminal removed") { nodes -> nodes.none { it.label(label(R.string.terminal_close_process)) } }
        terminal.refresh()
        assertTrue(terminal.state.value.terminals.isEmpty())
    }

    private suspend fun fileJourney(ui: NativeChatFixture, fixture: JsonObject, transport: DshTransport, files: FilesCoordinator,
        reader: DshSessionReader, directory: File, scope: CoroutineScope, failure: AtomicReference<Throwable?>) {
        val image = fixture.obj("image")
        val bytes = Base64.getDecoder().decode(image.string("data"))
        val name = image.string("name")
        val sessionId = fixture.string("sessionId")
        assertEquals(image.string("sha256"), digest(bytes))
        val before = reader.state.value.window!!.records.count { it.type == "user/message" }
        ui.content { FilesScreen(files, sessionId) {} }
        // This is a picker-owned source, not a substituted HTTP or file service.
        files.upload(sessionId, name, bytes.size.toLong()) { bytes.inputStream() }
        eventually(failure, "uploaded image receipt") { !files.state.value.busy && files.state.value.uploaded.size == 1 }
        assertNull(files.state.value.error)
        val uploaded = files.state.value.uploaded.single()
        assertEquals("sha256:${image.string("sha256")}", uploaded.attachmentId)
        ui.await("native upload remains an unsent receipt") { nodes -> nodes.any { it.contains(name) && it.contains("待发送") } }
        assertEquals(before, reader.state.value.window!!.records.count { it.type == "user/message" })
        val runtime = fixture.obj("pairing").string("runtimeId")
        val owner = fixtureOwner(fixture)
        val draft = mutableStateOf(MessageComposerState(ConversationResource(owner, runtime, runtime, sessionId), TextFieldValue("B11 explicit uploaded image 原样🙂")))
        ui.content {
            val read by reader.state.collectAsState()
            ChatComposer(HomeState(HomePhase.READY, reader = read, composer = draft.value, files = files),
                onEditDraft = { draft.value = draft.value.copy(value = it) }, onSend = {
                    val captured = draft.value.value.text
                    scope.launch {
                        reader.prompt(captured, UUID.randomUUID().toString(), receipts = files.state.value.uploaded.map { it.receiptId })
                        files.removeUpload(uploaded.receiptId)
                    }
                }, onCancel = {}, onOpenModels = {})
        }
        ui.click(contextString(R.string.chat_send))
        eventually(failure, "explicit receipt admitted to user message") {
            nativeEvents(transport, reader).any { event -> event.fields["type"] == "user/message".json() &&
                (event.obj("data").fields["content"] as? JsonArray)?.values?.any { part ->
                    val value = part as? JsonObject
                    (value?.fields?.get("attachment") as? JsonObject)?.fields?.get("attachmentId") == uploaded.attachmentId.json()
                } == true }
        }
        // This exact native-provider path was absent at Host startup and was created
        // only by the upload. Read it through the real authenticated /api/file handler.
        ui.content { FilesScreen(files, sessionId) {} }
        ui.act(AccessibilityNodeInfo.ACTION_SET_TEXT, { it.editable }, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, image.string("uploadedPath"))
        })
        ui.click("读取文件")
        eventually(failure, "native PNG bytes staged") { !files.state.value.busy && files.state.value.preview != null }
        assertNull(files.state.value.error)
        val preview = files.state.value.preview!!
        assertEquals("image/png", preview.mediaType)
        assertArrayEquals(bytes, preview.file.readBytes())
        ui.await("actual PNG preview rendered") { nodes -> nodes.any { it.description == name } }
        val exported = File(directory, "exported.png")
        val target = PrivateExport(exported)
        files.export(target)
        eventually(failure, "transactional export committed") { files.state.value.exported }
        ui.await("native export success rendered") { nodes -> nodes.any { it.contains("文件已保存到所选位置") } }
        assertTrue(target.committed)
        assertFalse(target.aborted)
        assertFalse(target.pending.exists())
        assertArrayEquals(bytes, exported.readBytes())
        files.dismiss()
        assertFalse(preview.file.exists())
        inlineImageJourney(ui, fixture, files, reader, directory, scope, failure)
    }

    private suspend fun inlineImageJourney(ui: NativeChatFixture, fixture: JsonObject, files: FilesCoordinator,
        reader: DshSessionReader, directory: File, scope: CoroutineScope, failure: AtomicReference<Throwable?>) {
        val image = fixture.obj("image")
        val original = Base64.getDecoder().decode(image.string("data"))
        val name = "native-inline.png"
        val sessionId = reader.sessionId
        ui.content { FilesScreen(files, sessionId) {} }
        files.selectImage(sessionId, name) { original.inputStream() }
        eventually(failure, "phone-preprocessed native image staged") { !files.state.value.busy && files.state.value.images.size == 1 }
        assertNull(files.state.value.error)
        val prepared = files.state.value.images.single()
        assertEquals(2, prepared.width); assertEquals(2, prepared.height)
        assertTrue(prepared.part.encodedBytes <= 256 * 1024)
        ui.await("phone preprocessing notice and unsent image") { nodes -> nodes.any { it.contains("移除元数据") } && nodes.any { it.contains(name) && it.contains("待发送") } }
        eventually(failure, "previous native image-independent turn idle") { !reader.state.value.running }
        val runtime = fixture.obj("pairing").string("runtimeId")
        val draft = mutableStateOf(MessageComposerState(ConversationResource(fixtureOwner(fixture), runtime, runtime, sessionId)))
        ui.content {
            val read by reader.state.collectAsState()
            ChatComposer(HomeState(HomePhase.READY, reader = read, composer = draft.value, files = files),
                onEditDraft = { draft.value = draft.value.copy(value = it) }, onSend = {
                    val captured = files.imagesFor(sessionId)
                    scope.launch {
                        reader.prompt(draft.value.value.text, UUID.randomUUID().toString(), images = captured)
                        files.consumeImages(captured)
                    }
                }, onCancel = {}, onOpenModels = {})
        }
        val sendLabel = contextString(R.string.chat_send)
        ui.await("image-only composer send is enabled") { nodes -> nodes.any { it.label(sendLabel) && it.enabled } }
        ui.click(sendLabel)
        eventually(failure, "durable native intrinsic image reference") { reader.state.value.items.any { item ->
            item.persisted && !item.live && item.blocks.any { it.kind == BlockKind.IMAGE && it.media?.name == name }
        } }
        val item = reader.state.value.items.first { row -> row.blocks.any { it.kind == BlockKind.IMAGE && it.media?.name == name } }
        val reference = item.blocks.first { it.kind == BlockKind.IMAGE }.media!!
        assertEquals(2, reference.width); assertEquals(2, reference.height)
        assertTrue(reference.bytes > 0)
        ui.content {
            val state by files.state.collectAsState()
            if (state.preview != null) FilesScreen(files, sessionId) {}
            else ConversationMessage(item, onQuote = null, onPreviewMedia = { media -> files.loadAttachment(sessionId, media) })
        }
        val previewLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.media_message_preview, name)
        ui.click(previewLabel)
        eventually(failure, "native session attachment bytes verified") { files.state.value.preview != null && !files.state.value.busy }
        assertNull(files.state.value.error)
        val preview = files.state.value.preview!!
        assertEquals(reference.attachmentId, "sha256:${preview.sha256}")
        assertEquals(reference.mediaType, preview.mediaType)
        assertEquals(reference.bytes, preview.sizeBytes)
        ui.await("native intrinsic image preview rendered") { nodes -> nodes.any { it.description == name } }
        val bytes = preview.file.readBytes()
        val exported = File(directory, "inline-export.bin")
        val target = PrivateExport(exported)
        files.export(target)
        eventually(failure, "native attachment export committed") { files.state.value.exported }
        assertTrue(target.committed); assertFalse(target.aborted)
        assertArrayEquals(bytes, exported.readBytes())
        files.dismiss()
        assertFalse(preview.file.exists())
    }

    private suspend fun cacheRestartJourney(ui: NativeChatFixture, factory: LanConnectionFactory, credentials: LanCredentials,
        signer: DeviceProofSigner, reader: DshSessionReader, cache: DshTranscriptCache, route: DshCommandRoute,
        scope: CoroutineScope, failure: AtomicReference<Throwable?>) {
        eventually(failure, "native cache source turn finished") { !reader.state.value.running }
        val through = reader.state.value.window!!.cursor
        eventually(failure, "real native records encrypted to disk") { cache.load()?.cursor?.let { it >= through } == true }
        reader.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val offlineTransport = factory.transport(credentials, signer).also { it.close() }
        val reopenedCache = EncryptedTranscriptCache(context).forRoute(route)
        val offline = DshSessionReader(offlineTransport, route.sessionId, scope, cache = reopenedCache)
        try {
            eventually(failure, "encrypted disk history restored stale") { offline.state.value.stale && offline.state.value.window != null }
            val state = offline.state.value
            assertTrue(state.readOnly)
            val text = state.items.first { row -> row.blocks.any { it.text.contains("B11 explicit uploaded image 原样🙂") } }
            val image = state.items.first { row -> row.blocks.any { it.kind == BlockKind.IMAGE } }
            val position = ChatReadingPosition().apply { following = false; key = text.id }
            ui.content {
                NativeChatTimeline(state, position, {}, {},
                    onMessageAction = { error("Read-only history must not expose a server message action") },
                    onPreviewMedia = { error("Cached media must not authorize a remote read") })
            }
            ui.await("decrypted native history is visible after restart") { nodes -> nodes.any { it.contains("B11 explicit uploaded image 原样🙂") } }
            ui.capture("native-encrypted-cache-restored")
            val previewLabel = context.getString(R.string.media_message_preview, "native-inline.png")
            ui.scrollTo({ it.label(previewLabel) }, forward = true)
            assertFalse(ui.actionNode { it.label(previewLabel) }.enabled)
            val denied = runCatching { offline.prompt("Offline cache must never authorize this prompt", UUID.randomUUID().toString()) }.exceptionOrNull()
            assertTrue(denied is DshFailure)
            assertEquals("session.read_only", (denied as DshFailure).code)
            assertTrue(state.window!!.records.none { it.type in setOf("approval/asked", "approval/decided", "user-questions/request") })
        } finally { offline.close(); offlineTransport.close() }
        val liveTransport = factory.transport(credentials, signer)
        val live = DshSessionReader(liveTransport, route.sessionId, scope, cache = reopenedCache)
        try {
            eventually(failure, "restarted encrypted cache reconciled with real native follow") { live.state.value.phase == SessionReadPhase.READY && !live.state.value.stale }
            assertTrue(live.state.value.items.any { row -> row.blocks.any { it.text.contains("B11 explicit uploaded image 原样🙂") } })
        } finally { live.close(); liveTransport.close(); reopenedCache.clear() }
    }

    private suspend fun interactionJourney(ui: NativeChatFixture, fixture: JsonObject, transport: DshTransport,
        reader: DshSessionReader, coordinator: InteractionCoordinator, scope: CoroutineScope, failure: AtomicReference<Throwable?>) {
        fun show() = ui.content {
            val state by coordinator.state.collectAsState()
            val session by reader.state.collectAsState()
            Column { InteractionCards(state.interactions,
                onApprove = { key, verify -> scope.launch { coordinator.approve(key, reader.sessionId, verify) } },
                onReject = { key -> scope.launch { coordinator.reject(key) } },
                onAnswers = { key, answers -> scope.launch { coordinator.answer(key, answers) } }, error = state.error,
                decisions = interactionDecisions(reader.sessionId, session), sessionId = reader.sessionId) }
        }
        coordinator.start(transport, reader.sessionId)
        eventually(failure, "native interaction channel online") { coordinator.state.value.phase == InteractionWatcher.Phase.ONLINE }
        show()
        reader.prompt("REMOTE_APPROVAL_ALLOW", UUID.randomUUID().toString())
        eventually(failure, "live allow request") { coordinator.state.value.interactions.any { it is PendingInteraction.Approval } }
        ui.act(AccessibilityNodeInfo.ACTION_FOCUS, { it.tag("approval-shortcuts") })
        ui.enter()
        eventually(failure, "waiting for the enrolled test biometric") {
            coordinator.state.value.interactions.filterIsInstance<PendingInteraction.Approval>().any { it.verifyingBiometric }
        }
        eventually(failure, "native allowed-once audit") { hasApprovalOutcome(transport, reader, "allowed-once") }
        val workspace = fixture.obj("artifacts").string("workspace")
        eventually(failure, "allowed tool turn finished") { !reader.state.value.running }
        assertEquals("authorized", download(transport, "$workspace/approval-allow.txt").toString(Charsets.UTF_8))
        reader.prompt("REMOTE_APPROVAL_REJECT", UUID.randomUUID().toString())
        eventually(failure, "live reject request") { coordinator.state.value.interactions.any { it is PendingInteraction.Approval } }
        ui.click(contextString(R.string.interaction_reject))
        eventually(failure, "native rejected audit") { hasApprovalOutcome(transport, reader, "rejected") }
        eventually(failure, "rejected request retired") { !reader.state.value.running && coordinator.state.value.interactions.isEmpty() }
        reader.prompt("REMOTE_APPROVAL_REVOKE", UUID.randomUUID().toString())
        eventually(failure, "live revocable request") { coordinator.state.value.interactions.any { it is PendingInteraction.Approval } }
        val retired = coordinator.state.value.interactions.filterIsInstance<PendingInteraction.Approval>().single().eventId
        reader.cancel()
        eventually(failure, "revoked action key retired") { coordinator.state.value.interactions.none { it.eventId == retired } }
        val stale = runCatching { coordinator.approve(retired, reader.sessionId, verify = { error("stale request must not prompt") }) }.exceptionOrNull()
        assertTrue(stale is DshFailure)
        eventually(failure, "native cancelled audit") { hasApprovalOutcome(transport, reader, "cancelled") }
        coordinator.stop(); coordinator.start(transport, reader.sessionId)
        eventually(failure, "replacement interaction channel online") { coordinator.state.value.phase == InteractionWatcher.Phase.ONLINE }
        // The model fixture's foreground question lasts 10s, leaving real API26/TCG
        // rendering time before we release its claim and exercise genuine late answering.
        reader.prompt("REMOTE_TIMED_FOREGROUND", UUID.randomUUID().toString())
        eventually(failure, "real foreground timed question") { coordinator.state.value.interactions.filterIsInstance<PendingInteraction.Question>().any { it.phase == PendingInteraction.QuestionPhase.OPEN && it.remainingMs != null } }
        val countdownLabel = Regex(".*[0-9]+.*(?:秒|second).*")
        ui.await("native timed question is foreground") { nodes -> nodes.any { countdownLabel.matches(it.text) } }
        // Leaving the answer UI releases its real native attachWait claim. The
        // Host's own timeout, not a test-created pending item, advances the turn.
        coordinator.stop()
        var lastQuestionProjection: JsonObject? = null
        try {
            eventually(failure, "native question continued after deadline") {
                val projection = transport.native("session/projections", jsonObject("request" to jsonObject("sessionId" to fixture.string("sessionId").json()))) as JsonObject
                DshMethods.SessionProjections.output.decode(projection)
                val values = projection.fields["values"] as? JsonObject
                val questions = values?.fields?.get("userQuestions") as? JsonObject
                lastQuestionProjection = questions
                (questions?.fields?.get("active") as? JsonArray)?.values?.any { (it as? JsonObject)?.fields?.get("state") == "continued".json() } == true
            }
        } catch (error: Throwable) {
            throw AssertionError("Native question claim did not settle: ${lastQuestionProjection?.let { JsonDocument.of(it).canonicalText() }}", error)
        }
        coordinator.start(transport, reader.sessionId)
        eventually(failure, "continued question action restored") { coordinator.state.value.interactions.filterIsInstance<PendingInteraction.Question>().any { it.phase == PendingInteraction.QuestionPhase.CONTINUED } }
        ui.await("continued native question visibly remains answerable") { nodes -> nodes.any { it.contains(contextString(R.string.interaction_question_continued)) } }
        ui.click("Reply"); ui.click(contextString(R.string.interaction_question_submit))
        eventually(failure, "native late answer admitted") { nativeEvents(transport, reader).any { event ->
            val data = event.obj("data")
            event.fields["type"] == "user/message".json() && (data.fields["source"] as? JsonObject)?.fields?.get("kind") == "user-question-reply".json() &&
                (data.fields["content"] as? JsonArray)?.values?.any { part -> ((part as? JsonObject)?.fields?.get("text") as? JsonString)?.value?.contains("Reply") == true } == true
        } }
        eventually(failure, "answered question retired") { coordinator.state.value.interactions.none { it is PendingInteraction.Question } }
        ui.await("answered native question removed") { nodes -> nodes.none { it.label(contextString(R.string.interaction_question_title)) } }
    }

    private suspend fun eventually(failure: AtomicReference<Throwable?>, reason: String, condition: suspend () -> Boolean) {
        check(withTimeoutOrNull(20_000) {
            while (!condition()) { failure.get()?.let { throw AssertionError("Native UI action failed", it) }; delay(50) }
            true
        } == true) { "Native consumer did not establish: $reason" }
    }
    private suspend fun nativeEvents(transport: DshTransport, reader: DshSessionReader): List<JsonObject> {
        val cursor = reader.state.value.window?.cursor ?: return emptyList()
        val result = transport.native("session/page", jsonObject("request" to jsonObject(
            "address" to jsonObject("kind" to "session".json(), "sessionId" to reader.sessionId.json()),
            "throughSeq" to cursor.json(), "maxMessages" to 100L.json())))
        DshMethods.SessionPage.output.decode(result)
        return (result as JsonObject).array("records").map { (it as JsonObject).obj("event") }
    }
    private suspend fun hasApprovalOutcome(transport: DshTransport, reader: DshSessionReader, outcome: String): Boolean =
        nativeEvents(transport, reader).any { it.fields["type"] == "approval/decided".json() && it.obj("data").fields["outcome"] == outcome.json() }
    private fun fixtureOwner(fixture: JsonObject): LocalDataOwner {
        val owner = fixture.obj("owner")
        return LocalDataOwner(owner.string("account_id"), owner.string("organization_id"), owner.string("deployment_sha256"), owner.string("user_id"), owner.string("device_id"))
    }
    private fun contextString(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private suspend fun download(transport: DshTransport, path: String): ByteArray = java.io.ByteArrayOutputStream().use { sink ->
        transport.download(FilePreview.exactPath(path), sink, 1024 * 1024); sink.toByteArray()
    }
    private class PrivateExport(private val destination: File) : FileExportTarget {
        val pending = File(destination.parentFile, destination.name + ".pending")
        @Volatile var committed = false
        @Volatile var aborted = false
        override fun open(): OutputStream { check(!destination.exists() && !pending.exists()); return pending.outputStream() }
        override fun commit() {
            check(pending.isFile && !destination.exists())
            Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            committed = true
        }
        override fun abort() { aborted = true; pending.delete(); destination.delete() }
    }
}
