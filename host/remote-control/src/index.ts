import type { Context } from '@deepseek-ai/cordis'
import z from '@deepseek-ai/schemastery'
import type {} from '@deepseek-ai/dsh-client-connection'
import type {} from '@deepseek-ai/dsh-api-gateway'
import type {} from '@deepseek-ai/dsh-api-remote-access'
import type {} from '@deepseek-ai/dsh-session'
import type {} from '@deepseek-ai/dsh-workspace'
import { NativeGateway } from './native-gateway.js'
import { RelayTunnel } from './relay-tunnel.js'
import type { HostCredentialProvider } from './host-connection-credential.js'
import { HostEnrolledRuntimeError, verifyEnrolledHostRuntime, type HostEnrolledRuntimeOptions } from './host-enrolled-runtime.js'
import { registerDesktopUI } from './desktop-ui.js'
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
export const inject = ['connection', 'typertGateway', 'remoteAccess', 'loader', 'sessions', 'workspaceRegistry']
export interface Config {
  enabled?: boolean
  runtimeId?: string
  relayUrl?: string
  applicationVersion?: string
  dshVersion?: string
  pluginVersion?: string
}
export const Config: z<Config> = z.object({
  enabled: z.boolean().default(false),
  runtimeId: z.string().default(''),
  relayUrl: z.string().default(''),
  applicationVersion: z.string().default(''),
  dshVersion: z.string().default(''),
  pluginVersion: z.string().default(''),
})

export async function apply(ctx: Context, config: Config = {}, credentials?: HostCredentialProvider): Promise<void> {
  if (!config.enabled) return
  const options = Config(config)
  if (!credentials || !options.relayUrl || !options.applicationVersion || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(options.runtimeId ?? '')) throw new Error('authenticated Relay and stable runtime identity required')
  const origin = new URL(options.relayUrl).origin
  const versions = Object.freeze({ dsh: options.dshVersion ?? '', application: options.applicationVersion ?? '', plugin: options.pluginVersion ?? '' })
  if (credentials.origin && credentials.origin !== origin) throw new Error('installation origin changed')
  const child = ctx.plugin({ name: 'tokenworks-mobile-tunnel', inject, apply(scope: Context) {
    const gateway = new NativeGateway(scope.connection, scope.typertGateway)
    const tunnel = new RelayTunnel({ origin, runtimeId: options.runtimeId!, versions, credentials, connection: scope.connection, remoteAccess: scope.remoteAccess, gateway,
      resources: () => ({ sessions: scope.sessions.list().map(session => session.id), workspaces: scope.workspaceRegistry.list().map(workspace => workspace.id) }) })
    registerDesktopUI(scope, () => ({ runtimeId: options.runtimeId!, relay: { origin, phase: tunnel.status() }, development: options.applicationVersion === 'local-development-fixture' }))
    scope.effect(() => async () => { gateway.close(); await tunnel.close() }, 'remote-control.native-tunnel')
  } })
  await child.await()
}

/** The distribution supplies installation credentials backed by its protected key owner. */
export function createRemoteControlPlugin(credentials: HostCredentialProvider) {
  return { name, inject, apply(ctx: Context, config: Config = {}) { return apply(ctx, config, credentials) } }
}

export function createEnrolledRemoteControlPlugin(enrollment: HostEnrolledRuntimeOptions) {
  if (!enrollment || !(enrollment.client?.signal instanceof AbortSignal) || !(enrollment.binding?.signal instanceof AbortSignal) || !(enrollment.credentials?.signal instanceof AbortSignal)) throw new HostEnrolledRuntimeError('host.enrolled_runtime.invalid_config')
  const lifetime = AbortSignal.any([enrollment.client.signal, enrollment.binding.signal, enrollment.credentials.signal])
  const credentials: HostCredentialProvider = {
    ...(enrollment.credentials.origin ? { origin: enrollment.credentials.origin } : {}), signal: lifetime,
    acquire(signal) { return enrollment.credentials.acquire(AbortSignal.any([signal, lifetime])) },
  }
  return { name, inject, async apply(ctx: Context, config: Config = {}) {
    if (!config.enabled) return
    await verifyEnrolledHostRuntime(enrollment, config.runtimeId ?? '', lifetime)
    return apply(ctx, config, credentials)
  } }
}
