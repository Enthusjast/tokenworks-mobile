# Android task-focused navigation

## Scope and baseline

The first implementation slice introduced the task-focused navigation in §16.2; the subsequent reading, input and interaction changes reuse the existing protocol and persistence boundaries. The historical baseline and its verification record remain below.

Baseline source: `HomeScreen.kt` previously placed registration success, refresh, notifications, updates, binding and logout before the runtime cards. Opening a displayed session required its separate `读取会话` button in `SessionsScreen.kt`. Three source-defined paths:

| Task | Previous path | New path |
| --- | --- | --- |
| Open an existing session | Registered home → scroll to computer → view sessions → read button | Sessions → current computer directory → tap row |
| Send text | Open session → existing queue/steer composer → existing coordinator | Unchanged after opening session |
| Review approval | Open session → approval entry → full paged JSON → explicit page acknowledgements → decision → platform identity | Unchanged after opening session |

These are path descriptions, not task-time measurements. Existing public synthetic screenshots are in the ignored `.local/android-showcase-20261027/screenshots/` gallery (23 images, API 36, 720 × 1280); the gallery's banner and separately expanded panels are not production navigation. This slice keeps before/after captures and actual verification logs in the ignored `.local/android-ui-navigation/` directory. A capture must record pixel dimensions, density, font scale, theme, build and scene. No production secure-window flag is disabled.

## Page and state inventory

| Area | States / owner | Boundary |
| --- | --- | --- |
| Account setup | Unconfigured/invalid config, sign-in, recovery failure, reauthentication, enrollment, enrollment unknown; `LoginController` | Outside main navigation; no synthetic authenticated fallback |
| Sessions | Current verified runtime, directory loading/error/empty, directory-only grant, readable/archived/unavailable rows | No all-runtime transcript fetch or unread inference |
| Computers | Authorized runtimes, unknown presence, list/create capabilities, details, binding entry | Runtime IDs remain distinct authority subjects, including same-name hosts |
| Settings | Account/device, notifications, updates, about, explicit logout | Account success and management are not session-list content |
| Chat | Connecting/syncing/live/offline/cache/denied, bounded history, outbox, questions/files/approval entry | Existing single reader and business coordinators |
| Approval | Current online context, paged complete action, identity, original decision reconciliation | Existing review coverage, expiry, rejection and callback retirement remain authoritative |
| Binding / creation | Existing staged business state, unknown result, reconciliation | No retained unsubmitted selections or confirmation authority in navigation |
| Platform work | Browser, biometric, document/save picker, installer | Existing operation tokens and account/navigation generations receive callbacks |

## Data-source decisions

| Display | Source | Fallback / restriction |
| --- | --- | --- |
| Computer label/version | Verified host alias and `RuntimeDirectoryEntry` | No inferred online state; full runtime/host/organization IDs in details |
| Session identity/workspace/state/update time | `SessionDirectoryEntry` | Short ID + workspace ID; timestamp means directory update, not last reply |
| Body read capability | Current runtime grant + `SessionDirectoryEntry.viewScope` | Directory-only row is not actionable; opening still uses controller validation |
| Task title/summary/unread count | Not supplied by current directory contract | Not fabricated; no bulk body reads to populate the list |
| Pending actions | Current foreground session's validated native state | Existing session entries only; not claimed as global inbox |

### Optional metadata and aggregation

No optional U5 protocol extension is included in this UI work. Task titles and message summaries need a separately versioned producer, authorization scope and deletion/retention contract; unread counts additionally need durable, account-isolated read cursors and revocation semantics. Local aliases would require account-isolated storage and deletion on sign-out/revocation. Cross-runtime aggregation and a complete pending-work list require a bounded, paginated authorized query independent of push; QR binding requires a versioned, expiry-checked payload and a non-Google-services implementation. Until those contracts are approved, the UI uses existing directory IDs and validated current-session references only. It does not infer unread counts, fetch all transcripts or claim a global inbox.

## Navigation and retirement contract

- Main destinations: Sessions (default), Computers, Settings. Chat, full approval, binding, creation, notification settings and application updates remain child pages, without bottom navigation.
- Navigation holds only destination, current directory runtime ID, an in-memory filter and bounded directory/reading positions. Recent visits contain resource identifiers only and are revalidated against current authorized rows before display or opening. No saved-instance-state authority, drafts, credentials, binding codes, approval handles, or question answers are stored.
- Navigation is scoped to the READY account/device composition. Leaving READY or changing identity destroys it; a new account does not inherit the old destination or directory position.
- Only the current runtime's directory is requested automatically. The first eligible authorized runtime supplies the initial list. Switching computer revalidates through `openRuntime`; opening a row uses `openSession` and its existing scoped ticket/read path.
- Back from chat calls the existing controller `back`, retiring the reader, question scope, approvals, uploads/exports and callbacks before rendering the same safe directory. The list's stable keys and in-memory position remain available within that account composition.
- Switching to Computers/Settings first leaves the current directory through controller `back`; no hidden child page owns a retained connection. Returning to Sessions re-queries the selected runtime rather than displaying an authority cache.
- A directory entered from Computers returns to Computers; settings child pages return to Settings. A notification's known-session path still resolves the exact resource independently of broader directory enumeration and returns to reminders.
- Background/foreground, grant changes, expiry and late platform results continue through existing controller generations. UI navigation must not make an old interaction executable again.

## Reading, interaction and delivery boundaries

- Chat uses stable message keys, an account-scoped bounded in-memory anchor and explicit bottom-follow. Opening a previously visited message restores only when its key exists in the newly authorized snapshot; older-page requests remain owned and bounded by the existing reader. A visible new-message action returns to the latest content.
- The composer keeps queue/steer selection, explicit offline-only saving, current attachment readiness and the original submission callback. Selecting, removing from this message and deleting a stored transfer record are distinct actions. Original delivery facts and identifiers are shown separately from native transcript messages.
- Complete approval projection traverses the validated immutable context, including arbitrary nested values, unknown keys and binding fields. It uses bounded Unicode-safe pages and explicit per-page acknowledgements plus independent consent. Switching structured/raw representation clears confirmations. Projection is computed off the main thread and never replaces canonical signature bytes; leaving the foreground retires the ephemeral review.
- Question summaries never claim timed ownership. Explicitly opening a form may claim the current eligible timed question; closing or retiring the handle releases it. Plan continuation requires the complete displayed plan and explicit review, and unsubmitted form state is not restored.
- Uploads distinguish preparation, scanning, host staging, message selection and local record deletion. Exports use only authorized sources and retain the existing readback-verified saved state. Phone confirmation, host confirmation and binding grant remain separate; creation, notification and update screens distinguish original-request reconciliation, background delivery and installation results.
- Current pending entries are limited to validated state in the active session and do not imply a complete cross-device inbox. Notification configuration is not a prerequisite for answering foreground interactions.

## Layout decision

Low-fidelity 320dp layout targets, not screenshots or claims that later chat/approval slices are implemented. Wider windows retain a bounded single-column directory (max 800dp) in this slice, without additional readers.

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 960 430" fill="none" stroke="var(--border)" stroke-width="1">
  <g font-size="14" font-family="sans-serif">
    <g transform="translate(8 8)">
      <rect width="300" height="412" rx="12"/>
      <path d="M0 56H300M0 356H300"/>
      <g fill="currentColor" stroke="none">
        <text x="16" y="32">会话</text><text x="186" y="32">刷新　新建</text>
        <text x="16" y="86">当前电脑 / 运行环境　⌄</text>
        <text x="16" y="114" fill="var(--muted)">仅当前获授权范围，非全量索引</text>
        <text x="16" y="160">会话 短 ID　　　　　　　　›</text>
        <text x="16" y="188" fill="var(--muted)">已知状态 / 工作区 / 目录更新时间</text>
        <text x="16" y="244">会话 短 ID　　　　　　　　›</text>
        <text x="16" y="272" fill="var(--muted)">整行进入；目录授权不授予读取权</text>
        <text x="28" y="390">会话　　　电脑　　　设置</text>
      </g>
      <path d="M16 210H284M16 294H284"/>
    </g>
    <g transform="translate(330 8)">
      <rect width="300" height="412" rx="12"/>
      <path d="M0 68H300M0 310H300"/>
      <g fill="currentColor" stroke="none">
        <text x="16" y="28">返回　会话名称　　　　　　更多</text>
        <text x="58" y="52" fill="var(--muted)">电脑 / 工作区 · 连接状态</text>
        <text x="16" y="110">消息正文</text>
        <text x="16" y="144">代码 / 工具摘要 / 文件引用</text>
        <text x="16" y="200" fill="var(--muted)">稳定阅读锚点，历史前插不抢位置</text>
        <text x="174" y="282">回到底部 ↓</text>
        <text x="16" y="340">待处理入口（存在时）</text>
        <text x="16" y="370">排队 / 补充　　　附件</text>
        <text x="16" y="396">消息输入　　　　　　　　发送</text>
      </g>
    </g>
    <g transform="translate(652 8)">
      <rect width="300" height="412" rx="12"/>
      <path d="M0 56H300M0 310H300"/>
      <g fill="currentColor" stroke="none">
        <text x="16" y="32">返回　完整工具审批</text>
        <text x="16" y="88">当前来源 / 有效期 / 工具</text>
        <text x="16" y="124">完整参数、工作区、资源</text>
        <text x="16" y="160" fill="var(--muted)">每个值可完整阅读；可切原 JSON</text>
        <text x="16" y="208">本页显式核对　　上一页 / 下一页</text>
        <text x="16" y="252">核对进度 / 全部确认</text>
        <text x="16" y="282" fill="var(--muted)">滚动不代表允许；拒绝独立可达</text>
        <text x="16" y="344">仅本次允许并确认身份</text>
        <text x="16" y="380">拒绝并确认身份 / 查询原结果</text>
      </g>
    </g>
  </g>
</svg>
```

Material surfaces, paired light/dark colors, system fonts, spacing tokens and growing controls are shared through `core:ui`. Icons use vectors with semantic labels for actions; decorative arrows have no duplicate announcement. Controls target at least 48dp and no critical content relies on color alone. No motion is needed for main-navigation changes.

## Initial navigation verification (commit 77b19cc)

2026-10-28, debug build, dedicated `tokenworks-mobile-remote-api26` emulator, Android 8.0 x86_64 under software emulation. Display override 720 × 1280 / 320 dpi; physical screenshot buffer 1080 × 1920. The synthetic Compose captures record their actual Compose root, density, font scale and theme in accompanying JSON. The device used its previously provisioned secure PIN and strong biometric; no enrollment or global ADB reset was performed for this run.

- `./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8' check :app:assembleDebug :app:assembleDebugAndroidTest` — passed, including Android lint and JVM checks.
- Actual default `MainActivity` startup displayed the unconfigured-school gate. The production window retained `FLAG_SECURE`; no authenticated demo mode was added.
- `DshAppShellTest` and `HomeNavigationTest`: 14 instrumentation cases passed. They cover list-only grants, single-row opening with the exact session ticket scope, same-name and colliding runtime identifiers, logout confirmation, narrow / 2× text / landscape actions, both back paths, real TLS reader retirement, account switching and known-session notification access without directory enumeration.
- The independently packaged synthetic showcase completed a ten-scene native walkthrough: directory → row → chat → back, computer details, settings → notifications / updates, logout confirmation and dark directory. This exercises presentation and navigation only, not a real server submission.
- Focused existing Android regressions: 69 instrumentation cases passed, including onboarding, account recovery, platform biometric enrollment callback, session/cache authorization, notifications, composer, questions, approval UI, binding, creation, uploads and updates. These were targeted runs, not the full device suite.
- The public synthetic baseline contains 14 saved states from the previous isolated showcase; the final shell has six light/dark and large-text synthetic captures with metadata. API 36 also captured seven earlier baseline images before that software emulator crashed. For the API 26 baseline, `wm size` reported the override but screenshots used the physical 1080 × 1920 buffer; no before/after pixel or timing comparison is claimed.
- Measured static text/background contrast for selected light/dark body, secondary, primary-button and error-container pairs ranged from 6.87:1 to 16.07:1. This does not replace full accessibility or contrast auditing.

The tested APKs and immutable logs/metadata are in ignored `.local/android-ui-navigation/`. Debug APK SHA-256: `1f0b29163f98a6ccea647693b180131a32738fa72c2928354aae8eb1cddf105c`; instrumentation APK: `6ee50a7cfd6f2cc62e764daaa5ddc5ffbb2c7e31e1986585458fafb80288e20e`. Synthetic screenshots demonstrate layout only; TLS/controller, platform key and physical-device evidence remain separate. School identity, physical-device/OEM background delivery, full U2–U6 performance and production release acceptance are not established by this slice.

## Expanded interaction and acceptance evidence

The subsequent reading, composer, approval, question and supporting-flow changes were exercised on the dedicated `dsh-remote-api26` Android 8.0 x86_64 software emulator, at 720 × 1280 / 320 dpi with the system-default font scale. These are debug builds and public synthetic fixtures, not production account data.

- `:app:assembleDebug :app:assembleDebugAndroidTest` and `check` passed after the final source changes. The application APK SHA-256 is `e10067925c5bd4afa67dd1961741127f53266bc56bfb001b16682bd34f79505b`.
- Six targeted batches on the earlier application APK `8b303e2cc50428cd5b421af16889a41a59fab8c3336dba883d65ed50c492b539` passed 122 instrumentation cases, including account/reader transitions, approval delivery with the emulator's existing strong biometric, question and chat delivery, file selection, export selection and the individual UI screens. These are targeted batches, not the complete device test suite.
- After the subsequent link accessibility action, attachment availability and owner-authority pending-hint changes, the current application APK passed 41 focused instrumentation cases (`ChatReadingTest`, `SessionComposerTest`, `SessionsScreenTest`, `HomeNavigationTest`, `DshAppShellTest`, `UploadsPanelTest`) with test APK `61bb36dd22a4aeab1b2c92eb3f3313da0b1572e03564b079dcb4d105d55e0d77`. The final test APK `f3aac85db2876fb2f25cd443214097bf8e1408b14869e80d25e892da4d8fe4c5` subsequently passed the question-form screenshot case; its only later change was the timing of that screenshot. Three reading, approval and question scenarios also passed with all three Android animation scales set to zero; the existing settings were restored. The emulator had no enabled accessibility service, so these checks do not claim a TalkBack walkthrough.
- A separately packaged synthetic showcase using the same fixture data as the pre-change baseline captured 11 current scenes, including directory, chat, offline, approval, question, transfers, binding, creation, notifications and updates. Both runs report the same display override and physical screenshot buffer; timestamps and scroll positions differ, so the images support visual review rather than pixel or timing comparisons. Real activity/Compose tests are separate from the showcase; the production `FLAG_SECURE` policy was not disabled.
- At 360dp width, default density-independent font scale and no attachments, the measured composer is 122dp high. In one debug long-history/incremental-update instrumentation sample on this software renderer, `gfxinfo` reported 137/137 janky frames and a 350ms median. This is a diagnostic observation, not a 60Hz performance pass or a device-independent benchmark. Startup, directory and network timing, peak memory, actual task completion time and comparative production performance remain unmeasured.

Synthetic screenshots, Compose root/density/font-scale metadata, raw instrumentation results and APK hashes remain under ignored `.local/uiux-acceptance-current/` and `.local/uiux-acceptance-final/`; pre-change captures remain under `.local/android-ui-navigation/before-api26/`. Physical-device/OEM behavior, TalkBack, production identity/server flows and release-build performance require separate acceptance and are not inferred from these fixtures.

## Content-first detail layout

The shared title bar now places its subtitle below the title/action row, using the existing medium title typography and an 800dp content limit (720dp for the binding, creation, notification and update forms). The surface and system insets remain full-width. Chat details use a labelled information icon; long account, host and version text is not silently removed.

Directory metadata remains a single-column hierarchy. Workspace abbreviations use the same collision-aware rule as session labels, restricted to the currently authorized directory; no additional transcript or directory requests populate these labels. The newest-request card keeps its proven delivery status and existing record route while avoiding a repeated full request ID or duplicate route for the same pending interruption. The latest-message control reserves footer space instead of covering transcript text. Compact tool headers only disclose the fields already in the validated display model.

Attachment and file actions expose the verb and complete filename on the actual button, including expanded/collapsed state where relevant. An edit above the existing 65,536 UTF-16-unit input limit leaves the original input untouched, explains the rejection and disables sending until another valid-length edit. These UI states are transient and do not change draft retention, authority or submission protocols.

The conversation footer is measured below the title bar, inside the available content area. Oversized mode and attachment controls scroll while input and sending stay fixed; navigation and IME insets are consumed once by the page. The main activity declares `adjustResize` before initial window focus for legacy Android IME insets. Production `FLAG_SECURE` protection is unchanged. The 2x attachment-dialog scenario uses an isolated, non-exported debug activity with actual Android font resources and verifies the dialog's applied font scale, rather than relying only on a parent density override. Native fixture captures require the actual Compose window to hold focus and refuse secure windows; an accessibility snapshot is not used as a proxy for window ownership.

Formatted text starts with its content rather than a format-toggle row. Code copying distinguishes a successful clipboard write from a rejected one; its original completeness checks remain. Tables share column widths and horizontal scrolling, with equal-height cells within each row. Approval navigation wraps to its actual reading-panel width without changing page coverage, consent, independent rejection or signature preparation.

Verification for this application artifact passed `check` (including lint), debug app/test APK assembly, and the app JVM suite in both debug and release variants: 29 cases per variant with zero failures, errors or skips. The owned API 26 software AVD completed the following device batches against the same application APK:

| Device scope | Passing cases |
| --- | ---: |
| Title, directory, composer, conversation and archive | 47 |
| Reading and file actions | 19 |
| Binding, creation, notifications, updates and questions | 29 |
| Complete approval review | 12 |
| Explicit account/controller, encrypted history and delivery fixture | 16 |
| Normal-motion total | 123 |
| Additional reduced-motion navigation, input and reading checks | 27 |

The reduced-motion run set all three Android animation scales to zero and restored their original values exactly. Viewport fixtures cover narrow, short, landscape, wide and 2x-text layouts; native keyboard, actual-Android-font attachment dialogs, question forms, clipboard feedback and table/approval surfaces were inspected. The account run explicitly enabled its fixture and used normal PIN confirmation and virtual fingerprint enrollment, without lowering key-creation or signing requirements. The real `MainActivity` was launched and its secure bit and `adjustResize` mode were observed; protected pixels were not captured. Earlier interrupted runs, system-overlaid captures and prerequisite failures are retained as diagnostics, not passing evidence.

Application SHA-256: `592c75bd4a192e9283579ddcc899a2f0a8aab4091a3169305b7109ce28e05b22`. Final test APK SHA-256: `377f12c1cbc4072d27e42ba8094ca0435ffb8e8d506131c425be2b1f1fd2a0ae`. Instrumentation ownership checks and an action locator were corrected during integration; per-batch harness identities, raw logs, images and exact restored settings are recorded under ignored `.local/android-ui-details-evidence/continuation/`, with the result index in `final-verification.json`.

These results are software-emulator and fixture evidence, not physical-device performance or hardware-backed biometric assurance. TalkBack was not installed, so no TalkBack walkthrough is claimed. Production school SSO and physical/OEM behavior remain outside this acceptance. Earlier acceptance records above are historical rather than substitutes for this build's results.

## Scoped session workflows

The current directory keeps its status/workspace filters and searches only acquired, currently contained rows. Opt-in display names use explicit `session.metadata.read`; missing or withdrawn names fall back to collision-aware identifiers. A same-runtime refresh may keep already-authorized rows visible with a disabled, last-directory explanation. It does not open a reader; stale selections, lost scope and directory errors cannot turn those rows into actionable authority.

Chat now separates information, file transfers, image preparation and original requests. User text, low-emphasis tool/reasoning disclosures and delivery facts retain independent meaning. Pending approvals/questions use their actual current keys; counting or navigating does not claim, answer or sign. The compact and fullscreen editors share one `TextFieldValue`, mode and revision. Offline saving is visible, IME candidate/Enter edits do not send, and returning from an editor keeps its current draft. An IME may be visible with zero occlusion height; back handling uses the visibility flag rather than inferring it from height. Insets remain consumed once.

Ordinary drafts are foreground-only, keyed by accepted account/device/deployment and full host/runtime/session. Background, identity changes and withdrawal clear them, except the existing token-bound document/camera consent exception. They are not process-restorable or stored in preferences/saved state. The owner caps entries and total characters, including one failed-input recovery snapshot. A failed save cannot overwrite a newer edit: users may keep the current input or explicitly confirm replacing text/mode with the original; no attachment readiness, request replay or sending is restored by that choice.

Model selection uses the existing reader receiver, scoped catalog cursor and immutable original command. Provider/model/effort labels do not determine identity. The page distinguishes loaded-item search, partial provider failure, unavailable peers and uncertain originals. Confirmation refers to the session selection checkpoint, not a global default/current-model projection.

Configured-workspace Git review exposes its actual HEAD/version/manifest and exclusions, rather than claiming a per-turn diff. Partial or binary patches cannot be copied as complete. Reopening a paged file starts a fresh patch cursor chain while still rejecting cycles within that chain. Message share requires an explicit preview and fresh confirmation handle; attachment-only messages remain forkable but do not acquire invented share text. Native forks preserve the inclusive persisted prefix and original conversation; the journal permits only one native call per original. Known child/checkpoint evidence survives partial outcomes. A settled checkpoint may coexist with a separately reported workspace-attachment failure; it is not a claim that every sub-operation succeeded.

Current cross-end acceptance separately exercised native title/workspace publication plus strict HTTP opt-in/withdrawal, and production Kotlin model/review consumers against normal Host, Go/PostgreSQL and TLS. The model scenario paged four opaque choices and recovered the same original after a lost status response. The review scenario compared six real Git patch byte streams, reopened a paged file, confirmed visible text, produced one actual native fork/checkpoint, preserved the source and retired proofs on sync/device revocation. These are deterministic offline-model/local-fixture results, not school SSO or commercial-model evidence. Image-specific independent authorization and migration scope are recorded in [Android files](android-files.md).

### Current verification artifact

The current debug build passed the repository Android `check`, application checks/lint and app/test APK assembly. On the separately created `dsh-session-workflow-owned-36` Android 16/API 36 x86_64 AVD (720×1280, 320 dpi, Windows WHPX), one final explicitly activated batch passed **166** UI/controller/storage cases. The batch includes the real platform IME visibility/back scenario, 2x actual-Android-font attachment layout, local model identity/effort controls, disabled partial-patch copy, unknown-original fork controls, image picker/decoder/private-camera-file rules, pending-item navigation and complete approval/plan review. Four separately activated SQLCipher migration cases passed, including encrypted 6→7 and partial child/outcome reopening. These are targeted batches, not an assertion that every device test was run.

The owned AVD received a normal test PIN and virtual fingerprint through the system enrollment UI; no production key or authentication requirement was weakened. The notification-dependent known-resource scenario used its normal Android notification permission. The final default `MainActivity` was launched: the unconfigured-school gate remained visible, and native window metadata showed `SECURE` and `adjust=resize`. No protected pixels were captured. Public synthetic captures, raw results and package identities remain under ignored `.local/android-session-workflow-evidence/`.

- App APK SHA-256: `033eec50a4cd7cb4d7c9c8fbb986ff34de64c401622bb53c1c4a041c26f7dd21`.
- Test APK SHA-256: `9badfea8b19a38726b94887023d6517cef4915f4b557bc388218f0f20dbe5e7a`.
- Migration test APK SHA-256: `80900fc411935d9a2aaca3e17505548a58622fb645fd5cb40159710c2a4eea33`.

Software/virtual-biometric results do not establish physical hardware key enforcement, OEM background delivery, commercial-model quality or school SSO. Unicode composition/input and hardware/zero-height IME state were exercised; a manual Pinyin-candidate and TalkBack walkthrough were not performed. No physical-device or release-frame-budget performance claim is made.

The final `npm run verify` completed contract regeneration checks, TypeScript checks/build and **462 passed / 1 skipped / 0 failed** Host cases. The final real Go/PostgreSQL/native-session composition also passed model paging/checkpoint/idempotency, legacy page-error compatibility, archive/cancel, approvals/questions, durable receipts and revocation. Its database privacy guard validates the strict bounded directory-name schema independently: an explicitly admitted native title may itself contain a short body-derived phrase; transcript/model-private data remains forbidden outside that field, and local paths/raw credentials remain forbidden throughout the dump. This is not a claim that title opt-in reveals no content. No document is included in either implementation commit.

After acceptance, the dedicated `dsh-session-workflow-owned-36` emulator was stopped and its disposable profile was removed. Existing unrelated emulator profiles were not removed; the public synthetic evidence and exercised package artifacts were retained.

## Android workspace presentation renewal

The Android presentation now uses one READY workspace: an authorized session drawer on compact/medium windows, a 304dp session column or 72dp rail on expanded windows, and an optional 360dp inspector. The column widths and the chat row model were revised again; see "Android workspace columns and turn rows" below. Short windows remain single-column; IME changes do not reclassify the actual window. Directory position is attached to only one list. Computers, reminders and settings retain their original controller retirement paths; known-session entries do not acquire broad directory access.

Chat uses an authorized title/status header, flat assistant content, naturally bounded user bubbles, adjacent process-only groups and a capsule editor. Errors, interrupted records, ordinary body text and media do not disappear into process summaries. Source message IDs, offsets and expanded source IDs are bounded presentation hints; model/diff/message-action tasks unmount the active chat and use an inert transcript backdrop. The compact/fullscreen editor shares the original draft value/revision. Opening the attachment menu does not launch a picker, and files/images retain independent eligibility. Delivery facts stay above input and original requests remain authoritative.

Model and diff presentation choices are held above the adaptive container for the current owner/resource/context. Diff lines share a horizontal offset and measured content width. Approval reading, consent, refusal, question claim/release, fresh share/fork confirmation, transfer results, notification references, update verification and appearance ownership retain their existing business boundaries. Account onboarding uses neutral OIDC/device language; it does not introduce password login, default credentials, permissions or a phone-local runtime. The known directory model-action projection gap remains unchanged rather than being repaired by widening scope.

### Verification for this presentation build

| Layer | Observed result |
| --- | --- |
| APP JVM suites | 62 tests, 0 skipped, 0 failures/errors |
| Network JVM suites | 153 tests, 0 skipped, 0 failures/errors |
| Session JVM suites | 41 tests, 0 skipped, 0 failures/errors |
| Default debug and instrumentation APK assembly | Passed |
| Android debug lint | Passed |
| Explicit existing-development deployment/CA debug assembly | Passed; installed with `-r`, without clearing app data |
| Protected backend/protocol source boundary | All 1,433 baseline file digests unchanged |
| Existing local TLS Discovery/JWKS | HTTP 200 with the explicit existing CA and certificate verification enabled; no model/tool request |
| Current device interaction, captures and performance | Blocked by initial device unlock; not claimed as passing |

Executed from `android/`: `./gradlew :app:testDebugUnitTest :core:network:test :core:session:test`, debug/test APK assembly and `:app:lintDebug`. New public native `ChatWorkspaceTest` scenarios avoid Espresso input injection, use actual Activity/UiAutomation/Window PixelCopy, and include viewport/theme/font combinations and a 60-second frame/anchor workload. They are compiled but **not executed** in this record.

The unchanged baseline failed on Pixel_10/API 37 before UI actions because the existing Espresso dependency reflects `InputManager.getInstance`, which that platform no longer exposes. The pre-existing API 36 profile also remained `RUNNING_LOCKED`; ordinary wake/dismiss requests did not unlock it. Neither PIN guesses, lock resets, fingerprint enrollment, dependency upgrades nor secure-window capture were used. Pixel_10 has TalkBack installed, but no walkthrough, manual Pinyin candidate, real IME/chooser, rotation, authenticated Android service smoke or frame/memory result is claimed for this build. A prepared native smoke test does not substitute for the three planned activated device batches or real-service interaction.

Current ignored evidence and installed artifact identities are under `.local/android-experience-renewal/`; baseline APKs are preserved separately for later same-input comparison. The installed package remains `cn.edu.ustc.tokenworks`; the development CA is debug-only and no backend service was restarted. These source/JVM/build results do not establish physical-tablet, physical-device, production-identity or release-frame-budget acceptance.

## Android workspace columns and turn rows

The READY workspace keeps one owner per resource and one reader per session. What changed is how the columns are computed and how a finished turn is drawn.

| Rule | Value |
| --- | --- |
| Window classes | compact when width < 600 dp or height < 480 dp; medium 600–839 dp; expanded ≥ 840 dp |
| Expanded navigation | 68 dp rail, or a 320 dp column when chat keeps ≥ 600 dp next to it |
| Session panel | 30% of the window snapped to 4 dp, clamped to 360–420 dp, shown only while chat keeps ≥ 400 dp |
| Drawer width | min(320 dp, window width − 24 dp) |
| Reading measure | 720 dp for chat, sheet content and the transcript backdrop |
| Vertical rhythm | 8 dp inside a turn, 24 dp between turns, 16 dp page gutter |
| Press feedback | 0.97 scale for 90 ms, skipped when animations are disabled |

`workspaceLayout` is a pure function of viewport, selected session, requested panel and the user's navigation preference, so the column choice is testable without a device. Opening the panel folds a 320 dp sidebar back to the rail instead of squeezing chat below its minimum.

### Turn rows

`projectChatTimeline` groups records by their native turn. User records carry no turn on the wire, so they take the next positioned turn, which keeps a prompt with the answer it produced.

- A finished turn folds its tool calls and pure-reasoning records into one `过程 · N 项` header, keeps the final answer as a normal message, and closes with a footnote of elapsed time, distinct steps and tool calls. The header expands to the original message keys.
- A run of three or more adjacent tool records groups even without an answer; shorter runs stay individual rows.
- A streaming turn, or a turn containing an interrupted or errored record, never folds, so partial and failed work is never hidden behind a summary.
- `ConversationItem` now exposes the native sequence, turn, step and record time used by this projection; the protocol surface is unchanged.

### Local read marks

Directory rows show a local unread dot per owner: the highest persisted sequence this device has displayed, stored in `session-read-v1-<ownerKey>` and capped at the 200 newest sessions. Marking happens when a session opens, when its persisted sequence advances, and when it closes. This is a client-side convenience only; it never claims or requests server-side read state, and losing the file only costs the dots.

### Verification for this column and row build

| Layer | Observed result |
| --- | --- |
| APP JVM suites | 71 tests, 0 skipped, 0 failures/errors |
| Session JVM suites | 43 tests, 0 skipped, 0 failures/errors |
| Debug and instrumentation APK assembly | Passed |
| Android debug lint | Passed |
| `ChatWorkspaceTest` (Pixel_10/API 37, native accessibility driver) | 10 cases passed |
| `NativeWorkspaceControllerTest` (existing owned account fixture) | 1 case passed |
| Captures | 24 chat viewport/theme/font combinations, drawer at 320/360/600 dp, navigation column at 840/1280 dp, folded and expanded turn |

Commands used from `android/`: `./gradlew :app:testDebugUnitTest :core:session:test`, `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`, and `adb shell am instrument -w -e class cn.edu.ustc.tokenworks.ChatWorkspaceTest …`. Captures and their JSON metadata are in the ignored `.local/uiux-parity/captures/` directory. The Compose-rule instrumentation suites remain unexecutable on API 37 because the pinned Espresso version reflects `InputManager.getInstance`; only the native accessibility suites ran. Light-theme evidence comes from the 2x font host, because the plain `ComponentActivity` host inherits the emulator's night mode.
