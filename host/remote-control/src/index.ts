import type { Context } from '@deepseek-ai/cordis'
import { join } from 'node:path'
import z from '@deepseek-ai/schemastery'
import type {} from '@deepseek-ai/dsh-client-connection'
import type {} from '@deepseek-ai/dsh-api-gateway'
import type {} from '@deepseek-ai/dsh-api-remote-access'
import type {} from '@deepseek-ai/dsh-session'
import type {} from '@deepseek-ai/dsh-workspace'
import { NativeGateway } from './native-gateway.js'
import { LanGateway } from './lan-gateway.js'
import { RelayTunnel } from './relay-tunnel.js'
import type { HostCredentialProvider } from './host-connection-credential.js'
import { HostEnrolledRuntimeError, verifyEnrolledHostRuntime, type HostEnrolledRuntimeOptions } from './host-enrolled-runtime.js'
import { registerDesktopUI } from './desktop-ui.js'
import { LanPairing } from './lan-pairing.js'
import { LanPairingStore } from './lan-pairing-store.js'
import { NoiseCarrier } from './noise-carrier.js'
import { RtcCarrierHost } from './rtc-carrier.js'
import { loadRtcConfig } from './rtc-config.js'
import { installRemoteTools } from './remote-tools.js'
import { isSupportedDshVersion } from './product-access.generated.js'
import { NotificationReferences } from './notification-references.js'
import { installWorkspaceExports } from './workspace-exports.js'
import { sessionDeletionPlugin } from './session-deletion.js'
export { NotificationReferences } from './notification-references.js'
export type { NotificationReferenceOptions, NotificationReferenceStatus } from './notification-references.js'
import type { JournalKeyProtector } from './journal-key-protection.js'
export { LanPairing } from './lan-pairing.js'
export { LanPairingStore } from './lan-pairing-store.js'
export type { LanPairingDescriptor, LanPairingIdentity } from './lan-pairing.js'
export interface LanSecurity { pairingProtector: JournalKeyProtector }
export type { HostConnectionCredential, HostCredentialProvider, HostHandshakeSigner, InstallationPublicJwk } from './host-connection-credential.js'
export { HostRestCredentialProvider, HostCredentialError } from './host-credentials.js'
export type { HostResourceIdentity, HostResourceAccess, HostResourceRequest, HostResourceAccessProvider, HostRestCredentialOptions } from './host-credentials.js'
export { HostResourceJwtProvider, HostResourceJwtError } from './host-resource-jwt.js'
export type { HostResourceJwtOptions, HostResourceTokenSource } from './host-resource-jwt.js'
export { HostEnrollmentClient } from './host-enrollment.js'
export { HostEnrollmentError } from './host-enrollment-transport.js'
export type { HostEnrollmentOptions, HostEnrollmentRequest, HostEnrollmentIntent, HostEnrollmentReceipt, HostEnrollmentDetails, HostEnrollmentReview, HostEnrollmentApprovalReview, HostEnrollmentConfirmation, HostEnrollmentBinding } from './host-enrollment.js'
export { HostEnrollmentRecoveryStore, HostEnrollmentRecoveryError } from './host-enrollment-recovery.js'
export type { HostEnrollmentRecoveryOptions } from './host-enrollment-recovery.js'
export { HostEnrollmentBindingStore, HostEnrollmentBindingError } from './host-enrollment-binding.js'
export type { HostEnrollmentBindingOptions } from './host-enrollment-binding.js'
export type { HostEnrollmentTerminal, HostEnrollmentTerminalCleanup } from './host-enrollment-terminal.js'
export { HostLocalEnrollmentError, presentHostEnrollmentOnTerminal, approveHostEnrollmentOnTerminal, runHostEnrollmentOnTerminal } from './host-local-enrollment.js'
export type { HostLocalEnrollmentOptions } from './host-local-enrollment.js'
export { HostTerminalEnrollmentWorkflow, HostTerminalEnrollmentWorkflowError } from './host-enrollment-workflow.js'
export type { HostTerminalEnrollmentWorkflowOptions } from './host-enrollment-workflow.js'
export type { JournalKeyProtector } from './journal-key-protection.js'
export { MacOSKeychainJournalProtector } from './macos-keychain-journal-protector.js'
export type { MacOSKeychainJournalOptions } from './macos-keychain-journal-protector.js'
export { LinuxSecretServiceJournalProtector } from './secret-service-journal-protector.js'
export type { LinuxSecretServiceJournalOptions } from './secret-service-journal-protector.js'
export { MacOSKeychainInstallationSigner } from './macos-keychain-installation-signer.js'
export type { MacOSKeychainInstallationSignerOptions } from './macos-keychain-installation-signer.js'
export { LinuxSecretServiceInstallationSigner } from './secret-service-installation-signer.js'
export type { LinuxSecretServiceInstallationSignerOptions } from './secret-service-installation-signer.js'
export { LinuxInstallationKeyStore, LinuxInstallationKeyError } from './linux-installation-key.js'
export type { LinuxInstallationKeyOptions, LinuxInstallationIdentity } from './linux-installation-key.js'
export { HostEnrolledRuntimeError } from './host-enrolled-runtime.js'
export type { HostEnrolledRuntimeOptions } from './host-enrolled-runtime.js'

export const name = 'tokenworks-mobile'
export const inject = ['connection', 'typert', 'typertGateway', 'remoteAccess', 'loader', 'sessions', 'workspaceRegistry']
export interface Config {
  enabled?: boolean
  runtimeId?: string
  relayUrl?: string
  applicationVersion?: string
  dshVersion?: string
  pluginVersion?: string
  /** Operator opt-in; disabled unless the service also configures a push provider. */
  push?: { enabled?: boolean; ttlSeconds?: number }
  /** Pairing trust root exists even with the LAN listener disabled. */
  pairing?: { directory: string; origin: string; certificateCert: string }
  lan?: { host: string; port: number; certificateKey: string }
  /** Operator-owned private RTC settings; never accepts model-supplied ICE credentials. */
  rtcConfigFile?: string
  /** Opt-in private local session store; never derive a deletion root from workspace paths. */
  sessionDeletionRoot?: string
}
export const Config: z<Config> = z.object({
  enabled: z.boolean().default(false),
  runtimeId: z.string().default(''),
  relayUrl: z.string().default(''),
  applicationVersion: z.string().default(''),
  dshVersion: z.string().default(''),
  pluginVersion: z.string().default(''),
  push: z.object({ enabled: z.boolean().default(false), ttlSeconds: z.number().default(300) }),
  pairing: z.object({ directory: z.string(), origin: z.string(), certificateCert: z.string() }),
  lan: z.object({ host: z.string(), port: z.number(), certificateKey: z.string() }),
  rtcConfigFile: z.string(),
  sessionDeletionRoot: z.string(),
})

export async function apply(ctx: Context, config: Config = {}, credentials?: HostCredentialProvider, security?: LanSecurity): Promise<void> {
  if (!config.enabled) return
  const options = Config(config)
  const versions = Object.freeze({ dsh: options.dshVersion ?? '', application: options.applicationVersion ?? '', plugin: options.pluginVersion ?? '' })
  if (!isSupportedDshVersion(versions.dsh)) throw new Error('unsupported dsh version')
  if (!credentials || !options.relayUrl || !options.applicationVersion || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(options.runtimeId ?? '')) throw new Error('authenticated Relay and stable runtime identity required')
  const origin = new URL(options.relayUrl).origin
  if (credentials.origin && credentials.origin !== origin) throw new Error('installation origin changed')
  const child = ctx.plugin({ name: 'tokenworks-mobile-tunnel', inject, async apply(scope: Context) {
    if (!security?.pairingProtector || !options.pairing) throw new Error('encrypted carrier requires protected pairing configuration')
    const rtcConfig = await loadRtcConfig(options.rtcConfigFile)
    const installationId = (await credentials.acquire(new AbortController().signal)).installationId
    const store = new LanPairingStore(options.pairing.directory, options.runtimeId!, installationId, security.pairingProtector)
    const pairing = await LanPairing.open({ origin: options.pairing.origin, runtimeId: options.runtimeId!, installationId }, options.pairing.certificateCert, store, scope.connection)
    installWorkspaceExports(scope)
    if (options.sessionDeletionRoot) {
      await scope.plugin(sessionDeletionPlugin(options.sessionDeletionRoot, versions.dsh)).await()
    }
    const gateway = new NativeGateway(scope.connection, scope.typertGateway)
    const carrier = new NoiseCarrier(pairing, gateway, versions.dsh, origin)
    carrier.setRtcControl(new RtcCarrierHost(pairing, carrier, rtcConfig))
    const tunnel = new RelayTunnel({ origin, runtimeId: options.runtimeId!, installationId, versions, credentials, connection: scope.connection, remoteAccess: scope.remoteAccess, gateway, carrier,
      resources: () => ({ sessions: scope.sessions.list().map(session => session.id), workspaces: scope.workspaceRegistry.list().map(workspace => workspace.id) }) })
    carrier.setRelayAuthority((id, secret, owner) => tunnel.admitFresh(id, secret, owner))
    const references = new NotificationReferences(scope, { origin, runtimeId: options.runtimeId!, credentials,
      enabled: options.push?.enabled === true, ttlSeconds: options.push?.ttlSeconds ?? 300 })
    let lan: LanGateway | undefined
    scope.effect(() => async () => {
      carrier.close()
      await carrier.drain()
      pairing.close(); gateway.close()
      await Promise.all([tunnel.close(), references.close(), lan?.close()])
    }, 'remote-control.native-tunnel')
    await installRemoteTools(scope, { dataDir: join(options.pairing.directory, 'remote-tools'),
      sourceInstallationId: installationId, sourceRuntimeId: options.runtimeId!,
      protector: security.pairingProtector, ...(credentials.signal ? { ownerSignal: credentials.signal } : {}) })
    if (options.lan) {
      if (!Number.isInteger(options.lan.port) || options.lan.port < 1 || options.lan.port > 65535) throw new Error('lan port out of range')
      lan = new LanGateway({ host: options.lan.host, port: options.lan.port, connection: scope.connection, gateway, carrier,
        certificate: { cert: options.pairing.certificateCert, key: options.lan.certificateKey }, pairing })
      await lan.listen()
    }
    registerDesktopUI(scope, () => ({ runtimeId: options.runtimeId!, relay: { origin, phase: tunnel.status() },
      lan: lan ? { host: options.lan!.host, port: options.lan!.port, phase: lan.phase(), ...pairing!.identity } : null,
      push: { phase: references.status() },
      development: options.applicationVersion === 'local-development-fixture' }), pairing)
  } })
  await child.await()
}

/** The distribution supplies installation credentials backed by its protected key owner. */
export function createRemoteControlPlugin(credentials: HostCredentialProvider, security?: LanSecurity) {
  return { name, inject, apply(ctx: Context, config: Config = {}) { return apply(ctx, config, credentials, security) } }
}

export function createEnrolledRemoteControlPlugin(enrollment: HostEnrolledRuntimeOptions, security?: LanSecurity) {
  if (!enrollment || !(enrollment.client?.signal instanceof AbortSignal) || !(enrollment.binding?.signal instanceof AbortSignal) || !(enrollment.credentials?.signal instanceof AbortSignal)) throw new HostEnrolledRuntimeError('host.enrolled_runtime.invalid_config')
  const lifetime = AbortSignal.any([enrollment.client.signal, enrollment.binding.signal, enrollment.credentials.signal])
  const credentials: HostCredentialProvider = {
    ...(enrollment.credentials.origin ? { origin: enrollment.credentials.origin } : {}), signal: lifetime,
    acquire(signal) { return enrollment.credentials.acquire(AbortSignal.any([signal, lifetime])) },
  }
  return { name, inject, async apply(ctx: Context, config: Config = {}) {
    if (!config.enabled) return
    await verifyEnrolledHostRuntime(enrollment, config.runtimeId ?? '', lifetime)
    return apply(ctx, config, credentials, security)
  } }
}
