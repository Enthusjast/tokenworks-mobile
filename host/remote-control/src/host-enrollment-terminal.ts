import { createHash } from 'node:crypto'
import { z } from 'zod'
import { enrollmentRecoveryBinding, enrollmentTerminalRecoveryInput, enrollmentTerminalTransport, hostEnrollmentBindingRecord,
  type HostEnrollmentBindingRecord, type HostEnrollmentClient } from './host-enrollment.js'
import { enrollmentError, HostEnrollmentError } from './host-enrollment-transport.js'

const uuid = z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
const ref = z.string().regex(/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/)
const version = z.string().max(128)
const reply = z.strictObject({ ok: z.literal(true), data: z.strictObject({ v: z.literal(1), enrollment_id: uuid, request_id: uuid,
  organization_id: uuid, host_id: uuid, host_alias: z.string().max(2048), installation_id: uuid,
  installation_key_jkt: z.string().regex(/^[A-Za-z0-9_-]{43}$/), runtime_id: uuid, profile_ref: ref,
  dsh_version: version, application_version: version, plugin_version: version, protocol_version: z.number().int().min(1).max(32),
  state: z.enum(['denied', 'expired', 'cancelled']), expires_at: z.string().datetime({ offset: true }), observed_at: z.string().datetime({ offset: true }),
  workspaces: z.array(z.strictObject({ reference: ref, workspace_id: uuid })).max(64),
}) })
type Wire = z.infer<typeof reply>['data']
export interface HostEnrollmentTerminal {
  readonly requestId: string
  readonly enrollmentId: string
  readonly state: 'denied' | 'expired' | 'cancelled'
  readonly observedAt: string
}
export interface HostEnrollmentTerminalCleanup {
  readonly terminal: HostEnrollmentTerminal
  readonly recoveryRemoved: boolean
  readonly bindingRemoved: boolean
}
type Owner = { binding: string; digest: string | undefined; wire: Wire }
const verified = new WeakMap<HostEnrollmentTerminal, Owner>()
const digest = (raw: Uint8Array) => createHash('sha256').update(raw).digest('hex')
function recoveryMatches(client: HostEnrollmentClient, raw: Uint8Array, row: Wire): boolean {
  const value = enrollmentTerminalRecoveryInput(client, raw)
  const refs = row.workspaces.map(item => item.reference).sort()
  return value.requestId === row.request_id && value.organizationId === row.organization_id && (!value.hostId || value.hostId === row.host_id) &&
    value.alias === row.host_alias && value.profileRef === row.profile_ref && value.dshVersion === row.dsh_version &&
    value.applicationVersion === row.application_version && value.pluginVersion === row.plugin_version && value.protocolVersion === row.protocol_version &&
    refs.length === value.workspaceRefs.length && refs.every((item, index) => item === value.workspaceRefs[index])
}
function recordMatches(record: HostEnrollmentBindingRecord, row: Wire): boolean {
  const ids = new Map(row.workspaces.map(item => [item.reference, item.workspace_id]))
  return new Set(record.workspaces.map(item => item.reference)).size === record.workspaces.length &&
    new Set(record.workspaces.map(item => item.workspace_id)).size === record.workspaces.length &&
    record.request_id === row.request_id && record.enrollment_id === row.enrollment_id && record.organization_id === row.organization_id &&
    record.host_id === row.host_id && record.installation_id === row.installation_id && record.installation_key_jkt === row.installation_key_jkt &&
    record.runtime_id === row.runtime_id && record.profile_ref === row.profile_ref && Date.parse(record.expires_at) === Date.parse(row.expires_at) &&
    record.workspaces.length === row.workspaces.length && record.workspaces.every(item => ids.get(item.reference) === item.workspace_id)
}
function owner(client: HostEnrollmentClient, terminal: HostEnrollmentTerminal): Owner {
  const value = verified.get(terminal)
  if (!value || value.binding !== enrollmentRecoveryBinding(client)) throw enrollmentError('identity_changed')
  return value
}
/** @internal A structural metadata object is never a verified terminal fact. */
export function requireEnrollmentTerminalOwner(client: HostEnrollmentClient, terminal: HostEnrollmentTerminal): void { owner(client, terminal) }
/** @internal Closed history is deliberately a different brand from approval. */
export function terminalMatchesBinding(client: HostEnrollmentClient, terminal: HostEnrollmentTerminal, source: HostEnrollmentBindingRecord): boolean {
  const record = hostEnrollmentBindingRecord.parse(source), value = owner(client, terminal)
  return record.binding === value.binding && recordMatches(record, value.wire) && (record.version !== 2 || record.recovery_sha256 === value.digest)
}
/** @internal Require the complete authenticated bytes, not just a request UUID. */
export function terminalMatchesRecovery(client: HostEnrollmentClient, terminal: HostEnrollmentTerminal, raw: Uint8Array): boolean {
  const value = owner(client, terminal)
  return value.digest === digest(raw) && recoveryMatches(client, raw, value.wire)
}
/** @internal Called only while both local paths are held by the cleanup coordinator. */
export async function observeEnrollmentTerminal(client: HostEnrollmentClient, source: HostEnrollmentBindingRecord | undefined,
  recovery: Uint8Array | undefined, signal: AbortSignal): Promise<HostEnrollmentTerminal> {
  const binding = enrollmentRecoveryBinding(client), transport = enrollmentTerminalTransport(client)
  const record = source && hostEnrollmentBindingRecord.parse(source)
  if (record && (record.binding !== binding || record.installation_key_jkt !== transport.thumbprint ||
      new Set(record.workspaces.map(item => item.reference)).size !== record.workspaces.length ||
      new Set(record.workspaces.map(item => item.workspace_id)).size !== record.workspaces.length)) throw enrollmentError('identity_changed')
  const input = recovery && enrollmentTerminalRecoveryInput(client, recovery)
  if (!record && !input) throw enrollmentError('invalid_config')
  const hash = recovery ? digest(recovery) : record?.version === 2 ? record.recovery_sha256 : undefined
  if (record && recovery && (record.version !== 2 || record.recovery_sha256 !== hash || record.request_id !== input!.requestId)) throw enrollmentError('identity_changed')
  const requestId = record?.request_id ?? input!.requestId
  let row: Wire
  try { row = reply.parse(await transport.request('POST', '/v1/enrollments/host-terminal', { request_id: requestId }, 200, signal)).data }
  catch (cause) { if (cause instanceof HostEnrollmentError) throw cause; throw enrollmentError('response_invalid') }
  if (enrollmentRecoveryBinding(client) !== binding || row.request_id !== requestId || row.installation_key_jkt !== transport.thumbprint ||
      new Set(row.workspaces.map(item => item.reference)).size !== row.workspaces.length || new Set(row.workspaces.map(item => item.workspace_id)).size !== row.workspaces.length ||
      record && !recordMatches(record, row) || recovery && !recoveryMatches(client, recovery, row)) throw enrollmentError('identity_changed')
  transport.current(signal)
  const terminal = Object.freeze({ requestId, enrollmentId: row.enrollment_id, state: row.state, observedAt: row.observed_at })
  verified.set(terminal, { binding, digest: hash, wire: row })
  return terminal
}
