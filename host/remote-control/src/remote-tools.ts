import type { Context } from '@deepseek-ai/cordis'
import { defineTool, type ToolRunContext, type ParameterSchemaSpec, type InferValue } from '@deepseek-ai/dsh-tools'
import type {} from '@deepseek-ai/dsh-user-approval'
import type {} from '@deepseek-ai/dsh-agent'
import type { SessionId } from '@deepseek-ai/dsh-session/types'
import type { SessionPromptRequest } from '@deepseek-ai/dsh-api-session-controller/types'
import type { FileUploadValue } from '@deepseek-ai/dsh-client-file-upload/types'
import type { RemoteResult } from '@deepseek-ai/dsh-typert-protocol'
import { join, posix } from 'node:path'
import { RemoteTargetStore, REMOTE_PAIRING_RISK, type RemoteStoreOptions, type RemoteTarget, type RemoteCredential } from './remote-targets.js'
import { RemoteOperationLedger } from './remote-operation-ledger.js'
import { connectRemoteNative, type RemoteNativeClient } from './remote-native-client.js'
import { RemoteFileSpool } from './remote-file-spool.js'
import { RemoteToolError, nativeValue, waitForRemotePrompt } from './remote-wait.js'
import { registerRemoteToolsUI } from './remote-tools-ui.js'

interface ToolValue { targetId?: string; operationId?: string; state: string; data: unknown }
const requiredString = { type: 'string', required: true } as const
const outputSchema = { type: 'object', additionalProperties: false, properties: {
  targetId: { type: 'string' }, operationId: { type: 'string' }, state: { type: 'string', required: true }, data: { type: 'json', required: true },
} } as const
function strictArgs(args: unknown, keys: string[]): asserts args is Record<string, unknown> {
  if (!args || typeof args !== 'object' || Array.isArray(args) || Object.keys(args).some(key => !keys.includes(key))) throw new RemoteToolError('remote/invalid-arguments')
}
function text(value: unknown, max = 200): string {
  if (typeof value !== 'string' || !value || Buffer.byteLength(value) > max || /[\u0000\u202a-\u202e\u2066-\u2069]/u.test(value)) throw new RemoteToolError('remote/invalid-arguments')
  return value
}
function jsonData(value: ToolValue): InferValue<typeof outputSchema> { return JSON.parse(JSON.stringify(value)) as InferValue<typeof outputSchema> }

export class RemoteToolsRuntime {
  readonly targets: RemoteTargetStore
  readonly ledger: RemoteOperationLedger
  readonly spool: RemoteFileSpool
  private readonly active = new Map<AbortController, ReadonlySet<string>>()
  private readonly approvals = new Map<object, { controller: AbortController; targetIds: ReadonlySet<string> }>()
  private readonly lifetime = new AbortController()
  private readonly drains = new Set<Promise<void>>()
  private readonly unhook: () => void
  constructor(readonly ctx: Context, readonly options: RemoteStoreOptions) {
    this.targets = new RemoteTargetStore(options); this.ledger = new RemoteOperationLedger(options)
    this.spool = new RemoteFileSpool(join(options.dataDir, 'remote-spool'))
    this.unhook = this.targets.onFence(targetId => {
      for (const [controller, ids] of this.active) if (!targetId || ids.has(targetId)) controller.abort(new RemoteToolError('remote/target-fenced'))
      for (const pending of this.approvals.values()) if (!targetId || pending.targetIds.has(targetId)) pending.controller.abort(new RemoteToolError('remote/target-fenced'))
    })
  }
  async status() { return { ...await this.targets.status(), caller: await this.targets.owner(), targets: await this.targets.list(), operations: await this.ledger.list(), sourceInstallationId: this.options.sourceInstallationId, sourceRuntimeId: this.options.sourceRuntimeId } }
  async close(): Promise<void> { this.lifetime.abort(); this.unhook(); await Promise.allSettled([...this.drains]); this.targets.close(); this.ledger.close(); await this.spool.close() }
  private agent(exec: ToolRunContext) {
    exec.signal.throwIfAborted(); this.lifetime.signal.throwIfAborted()
    if (!exec.agent || this.ctx.agents.get(exec.agent.id) !== exec.agent) throw new RemoteToolError('remote/source-agent-required')
    return exec.agent
  }
  private async target(exec: ToolRunContext, targetId: unknown, action: string) {
    this.agent(exec)
    const target = await this.targets.target(text(targetId, 80)); await this.targets.assertCurrent(target)
    if (!(target.allowedActions as readonly string[]).includes(action)) throw new RemoteToolError('remote/action-denied')
    return target
  }
  private async connected<T>(target: RemoteTarget, exec: ToolRunContext, action: (client: RemoteNativeClient, signal: AbortSignal) => Promise<T>, dependencies: readonly RemoteTarget[] = []): Promise<T> {
    if (this.active.size >= 32 || [...this.active.values()].some(ids => ids.has(target.id))) throw new RemoteToolError('remote/connection-limit')
    const controller = new AbortController(), signal = AbortSignal.any([exec.signal, this.targets.signal, this.lifetime.signal, controller.signal, AbortSignal.timeout(120_000)])
    const drained = Promise.withResolvers<void>(); this.drains.add(drained.promise)
    this.active.set(controller, new Set([target.id, ...dependencies.map(value => value.id)]))
    let client: RemoteNativeClient | undefined, credential: RemoteCredential | undefined
    try { credential = await this.targets.credential(target.id, signal); await this.targets.assertCurrent(target); for (const dependency of dependencies) await this.targets.assertCurrent(dependency); signal.throwIfAborted(); client = await connectRemoteNative(target, credential, signal); return await action(client, signal) }
    finally { try { await client?.close() } finally { credential?.close(); this.active.delete(controller); this.drains.delete(drained.promise); drained.resolve() } }
  }
  private async mutation<T>(target: RemoteTarget, exec: ToolRunContext, kind: string, body: object | ((requestId: string) => object), dispatch: (body: Record<string, unknown>, operationId: string) => Promise<T>, review?: unknown, dependencies: readonly RemoteTarget[] = []): Promise<ToolValue> {
    const agent = this.agent(exec)
    const { operation, created } = await this.ledger.prepare({ ownerSha256: target.ownerSha256, sourceAgentId: agent.id, callId: exec.callId, rootCallId: exec.rootCallId, target, kind, body })
    if (!created) return { targetId: target.id, operationId: operation.operationId, state: operation.state, data: { requestId: operation.requestId, replayed: false } }
    if (this.approvals.size >= 16 || this.approvals.has(agent)) { await this.ledger.record(operation.operationId, 'rejected', { code: 'remote/approval-limit' }); throw new RemoteToolError('remote/approval-limit') }
    const approvedBody = JSON.parse(Buffer.from(await this.ledger.body(operation.operationId)).toString('utf8')) as Record<string, unknown>
    const approvalController = new AbortController(), approvalSignal = AbortSignal.any([exec.signal, this.targets.signal, this.lifetime.signal, approvalController.signal, AbortSignal.timeout(120_000)])
    this.approvals.set(agent, { controller: approvalController, targetIds: new Set([target.id, ...dependencies.map(value => value.id)]) })
    let dispatched = false
    try {
      const audit = { targetId: target.id, installationId: target.descriptor.installationId, runtimeId: target.descriptor.runtimeId, hostNoisePublicKey: target.descriptor.hostStaticPublicKey, hostKeyEpoch: target.descriptor.hostKeyEpoch, authorizationEpoch: target.authorizationEpoch, sourceInstallationId: this.options.sourceInstallationId, sourceRuntimeId: this.options.sourceRuntimeId, sourceOwnerSha256: target.ownerSha256, sourceAgentId: agent.id, callId: exec.callId, operationId: operation.operationId, requestId: operation.requestId, digest: operation.digest, body: approvedBody, ...(review ? { fileAuthorization: review } : {}), authority: REMOTE_PAIRING_RISK }
      const preview = JSON.stringify(audit)
      const outcome = await this.ctx.approval.request({ agent, toolName: `remote_${kind}`, callId: exec.callId, reason: `Remote ${kind}; target=${target.id}; operation=${operation.operationId}; sha256=${operation.digest}`, displayReason: { en: preview, 'zh-CN': preview }, signal: approvalSignal })
      if (outcome !== 'allowed-once') throw new RemoteToolError(`remote/approval-${outcome}`)
      approvalSignal.throwIfAborted(); this.agent(exec); await this.targets.assertCurrent(target); for (const dependency of dependencies) await this.targets.assertCurrent(dependency)
      await this.ledger.markDispatchStarted(operation.operationId)
      dispatched = true
      approvalSignal.throwIfAborted(); await this.targets.assertCurrent(target); for (const dependency of dependencies) await this.targets.assertCurrent(dependency)
      const result = await dispatch(approvedBody, operation.operationId)
      await this.ledger.record(operation.operationId, 'accepted', result)
      return { targetId: target.id, operationId: operation.operationId, state: 'accepted', data: result }
    } catch (error) {
      if (dispatched) await this.ledger.record(operation.operationId, 'outcome-unknown', { code: error instanceof RemoteToolError ? error.code : 'remote/dispatch-unknown' }).catch(() => {})
      else await this.ledger.record(operation.operationId, error instanceof RemoteToolError && error.code.startsWith('remote/approval-') ? 'rejected' : 'cancelled-before-dispatch', { code: error instanceof RemoteToolError ? error.code : 'remote/not-dispatched' }).catch(() => {})
      throw error
    } finally { this.approvals.delete(agent) }
  }
  register(scope: Context): void {
    const register = (name: string, description: string, parameters: ParameterSchemaSpec, keys: string[], execute: (args: Record<string, unknown>, exec: ToolRunContext) => Promise<ToolValue>) => scope.effect(() => scope.tools.register(defineTool({
      name, description, parameters, timeoutMs: 120_000, isConcurrencySafe: () => false,
      output: { schema: outputSchema, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] },
      execute: async (args, exec) => { strictArgs(args, keys); return jsonData(await execute(args, exec)) },
    })))
    register('remote_discover', 'List only operator-paired direct targets. No network scanning, Relay impersonation, URL configuration, or automatic mutation retry.', { targetId: { type: 'string' } }, ['targetId'], async (args, exec) => {
      this.agent(exec)
      if (args.targetId === undefined) { const status = await this.targets.status(); return { state: status.enabled ? 'configured' : 'disabled', data: (await this.targets.list()).filter(target => target.allowedActions.includes('discover')).map(target => ({ targetId: target.id, displayName: target.displayName, installationId: target.descriptor.installationId, runtimeId: target.descriptor.runtimeId, state: target.status, route: 'direct-only', pairingAuthority: target.status === 'paired' ? 'full-native-owner' : 'not-authorized' })) } }
      const target = await this.target(exec, args.targetId, 'discover')
      return this.connected(target, exec, async () => ({ targetId: target.id, state: 'authenticated', data: { installationId: target.descriptor.installationId, runtimeId: target.descriptor.runtimeId, route: 'direct-only' } }))
    })
    register('remote_workspaces', 'Read the real native workspace baseline, limited to exact operator-approved workspace identities and roots.', { targetId: requiredString }, ['targetId'], async (args, exec) => {
      const target = await this.target(exec, args.targetId, 'workspaces')
      return this.connected(target, exec, async (client, signal) => {
        const handle = client.remote.workspace.follow(signal)
        try { for await (const frame of handle) { if (frame.type !== 'baseline') throw new RemoteToolError('remote/workspace-baseline-required'); if (frame.value.items.length > 200) throw new RemoteToolError('remote/list-budget'); return { targetId: target.id, state: 'observed', data: frame.value.items.filter(item => target.workspaces.some(allowed => allowed.workspaceId === item.workspaceId && allowed.canonicalRoot === item.path)) } } }
        finally { handle.dispose() }
        throw new RemoteToolError('remote/workspace-baseline-unavailable')
      })
    })
    register('remote_create_session', 'Create one native target session after source Agent-scoped owner approval. Unknown outcome is never replayed.', { targetId: requiredString, workspaceId: requiredString, agentPreset: requiredString }, ['targetId', 'workspaceId', 'agentPreset'], async (args, exec) => {
      const target = await this.target(exec, args.targetId, 'create_session'), workspaceId = text(args.workspaceId)
      if (!target.workspaces.some(value => value.workspaceId === workspaceId)) throw new RemoteToolError('remote/workspace-denied')
      const agentPreset = args.agentPreset === undefined ? undefined : text(args.agentPreset)
      if (!agentPreset || !target.allowedAgentPresets.includes(agentPreset)) throw new RemoteToolError('remote/agent-preset-denied')
      return this.mutation(target, exec, 'create_session', { workspaceId, agentPreset }, async body => this.connected(target, exec, async client => {
        const result = nativeValue(await client.remote.session.create(body))
        const sessionId = text(result.sessionId)
        await this.targets.bindSession(target.id, { sessionId, workspaceId, sourceAgentId: this.agent(exec).id }, target.authorizationEpoch)
        return { sessionId, workspaceId, agentPreset: result.agentPreset ?? agentPreset }
      }))
    })
    register('remote_prompt', 'Submit exactly one approved native prompt to an authorized session. accepted is admission, not completion. File references are prior upload operation handles.', { targetId: requiredString, sessionId: requiredString, text: { type: 'string' }, mode: { type: 'string', enum: ['queue', 'steer'] }, uploads: { type: 'array', items: { type: 'string' } } }, ['targetId', 'sessionId', 'text', 'mode', 'uploads'], async (args, exec) => {
      const target = await this.target(exec, args.targetId, 'prompt'), sessionId = text(args.sessionId) as SessionId
      await this.targets.assertSession(target.id, sessionId, this.agent(exec).id)
      const content: SessionPromptRequest['content'][number][] = []
      if (args.text !== undefined) content.push({ type: 'text', text: text(args.text, 256 * 1024) })
      if (args.uploads !== undefined) {
        if (!Array.isArray(args.uploads) || args.uploads.length > 16) throw new RemoteToolError('remote/invalid-arguments')
        for (const handle of args.uploads) {
          const upload = await this.ledger.get(text(handle)), input = JSON.parse(Buffer.from(await this.ledger.body(upload.operationId)).toString('utf8')) as Record<string, unknown>
          if (upload.kind !== 'files_upload' || upload.state !== 'accepted' || upload.targetId !== target.id || upload.targetEpoch !== target.authorizationEpoch || upload.sourceAgentId !== this.agent(exec).id || input.sessionId !== sessionId) throw new RemoteToolError('remote/receipt-binding')
          const receipt = upload.evidence as unknown as FileUploadValue
          content.push({ type: 'file', receiptId: receipt.receiptId })
        }
      }
      if (!content.length || (args.mode !== undefined && args.mode !== 'queue' && args.mode !== 'steer')) throw new RemoteToolError('remote/invalid-arguments')
      return this.mutation(target, exec, 'prompt', requestId => ({ sessionId, requestId, mode: args.mode ?? 'queue', content }), async body => this.connected(target, exec, async (client, signal) => {
        const result = nativeValue(await client.remote.session.prompt(body as unknown as SessionPromptRequest, signal))
        if (result.accepted !== true) throw new RemoteToolError('remote/prompt-response')
        return { accepted: true, requestId: body.requestId, sessionId }
      }))
    })
    register('remote_wait', 'Read native events for an exact prompt operation rpcId and its enclosing turn. Other idle/turn-end never means success. No remote cancellation.', { operationId: requiredString, timeoutMs: { type: 'integer' } }, ['operationId', 'timeoutMs'], async (args, exec) => this.observePrompt(args, exec, false))
    register('remote_reconcile', 'Read-only evidence for an existing operation; never repeats create/prompt/upload. Missing evidence stays unknown.', { operationId: requiredString }, ['operationId'], async (args, exec) => this.observePrompt(args, exec, true))
    register('remote_files', 'Native scoped list/read/stat or approved exact-path download/private-handle upload. No arbitrary local paths, URLs, cross-session receipts, or implicit export.', { targetId: requiredString, sessionId: requiredString, action: { type: 'string', required: true, enum: ['list', 'stat', 'read', 'download', 'upload'] }, path: { type: 'string' }, fileHandle: { type: 'string' }, name: { type: 'string' } }, ['targetId', 'sessionId', 'action', 'path', 'fileHandle', 'name'], async (args, exec) => this.files(args, exec))
  }
  private async observePrompt(args: Record<string, unknown>, exec: ToolRunContext, reconcile: boolean): Promise<ToolValue> {
    const operation = await this.ledger.get(text(args.operationId)), target = await this.target(exec, operation.targetId, 'wait')
    if (operation.targetEpoch !== target.authorizationEpoch || operation.ownerSha256 !== target.ownerSha256 || operation.sourceAgentId !== this.agent(exec).id) throw new RemoteToolError('remote/operation-binding')
    if (operation.kind !== 'prompt') {
      if (!reconcile) throw new RemoteToolError('remote/prompt-operation-required')
      const input = JSON.parse(Buffer.from(await this.ledger.body(operation.operationId)).toString('utf8')) as Record<string, unknown>
      if (operation.kind === 'files_download') {
        const file = await this.spool.metadata(text(input.fileHandle), target.ownerSha256, exec.signal)
        if (file.sourceTargetId !== target.id || file.sourceTargetEpoch !== target.authorizationEpoch || file.sessionId !== input.sessionId || file.path !== input.path || file.bytes !== input.bytes || file.sha256 !== input.sha256) throw new RemoteToolError('remote/file-binding')
        const verified = await this.spool.read(file, exec.signal); verified.fill(0)
        if (operation.state === 'outcome-unknown') await this.ledger.record(operation.operationId, 'accepted', file)
        return { targetId: target.id, operationId: operation.operationId, state: 'accepted', data: { file, evidence: 'verified-private-publication', readOnly: true } }
      }
      return this.connected(target, exec, async (client, signal) => {
        let observed: unknown
        if (operation.kind === 'create_session') {
          const sessions = nativeValue(await client.remote.session.list({}, signal))
          if (sessions.items.length > 200) throw new RemoteToolError('remote/list-budget')
          const root = target.workspaces.find(workspace => workspace.workspaceId === input.workspaceId)?.canonicalRoot
          observed = { sessions: sessions.items.filter(session => root !== undefined && session.cwd === root), automaticallyClaimed: false }
        } else {
          const sessionId = text(input.sessionId) as SessionId
          await this.targets.assertSession(target.id, sessionId, this.agent(exec).id)
          observed = { projections: nativeValue(await client.remote.session.projections({ sessionId }, signal)), receiptLookupUnsupported: true }
        }
        return { targetId: target.id, operationId: operation.operationId, state: operation.state, data: { retainedEvidence: operation.evidence ?? null, observed, readOnly: true, unknownCannotProveNotExecuted: operation.state === 'outcome-unknown' } }
      })
    }
    const body = JSON.parse(Buffer.from(await this.ledger.body(operation.operationId)).toString('utf8')) as SessionPromptRequest
    await this.targets.assertSession(target.id, body.sessionId, this.agent(exec).id)
    const timeout = reconcile ? 10_000 : args.timeoutMs === undefined ? 120_000 : Number(args.timeoutMs)
    return this.connected(target, exec, async (client, signal) => {
      const evidence = await waitForRemotePrompt(client.remote, body.sessionId, operation.requestId, signal, timeout)
      await this.ledger.record(operation.operationId, evidence.state === 'turn-ended' ? 'observed-terminal' : operation.state, evidence)
      return { targetId: target.id, operationId: operation.operationId, state: evidence.state, data: evidence }
    })
  }
  private async files(args: Record<string, unknown>, exec: ToolRunContext): Promise<ToolValue> {
    const action = text(args.action, 16), target = await this.target(exec, args.targetId, `files.${action}`), sessionId = text(args.sessionId) as SessionId
    const binding = await this.targets.assertSession(target.id, sessionId, this.agent(exec).id)
    if (action === 'upload') {
      if (args.path !== undefined) throw new RemoteToolError('remote/invalid-arguments')
      const file = await this.spool.metadata(text(args.fileHandle), target.ownerSha256, exec.signal)
      const source = await this.targets.target(file.sourceTargetId); await this.targets.assertCurrent(source)
      if (source.authorizationEpoch !== file.sourceTargetEpoch || source.descriptor.installationId !== file.sourceInstallationId || source.descriptor.runtimeId !== file.sourceRuntimeId) throw new RemoteToolError('remote/file-source-binding')
      await this.targets.assertSession(source.id, file.sessionId, this.agent(exec).id)
      const name = args.name === undefined ? file.name : text(args.name, 200)
      if (name.includes('/') || name.includes('\\') || /[\x00-\x1f\x7f]/.test(name)) throw new RemoteToolError('remote/file-name')
      const review = { source: file, destination: { targetId: target.id, installationId: target.descriptor.installationId, runtimeId: target.descriptor.runtimeId, sessionId, name }, bytes: file.bytes, sha256: file.sha256, noArbitraryWorkspaceWrite: true }
      return this.mutation(target, exec, 'files_upload', { sessionId, name, fileHandle: file.handle, bytes: file.bytes, sha256: file.sha256, sourceTargetId: source.id, sourceTargetEpoch: source.authorizationEpoch, sourceInstallationId: source.descriptor.installationId, sourceRuntimeId: source.descriptor.runtimeId, sourceNoisePublicKey: source.descriptor.hostStaticPublicKey, sourceHostKeyEpoch: source.descriptor.hostKeyEpoch, sourceSessionId: file.sessionId, sourcePath: file.path }, async () => {
        await this.targets.assertCurrent(source)
        const bytes = await this.spool.read(file, exec.signal)
        try { return await this.connected(target, exec, async (client, signal) => {
          const response = await client.fetch(`/api/session/uploadFileBinary?sessionId=${encodeURIComponent(sessionId)}&name=${encodeURIComponent(name)}`, { method: 'POST', body: bytes, signal })
          if (!response.ok) throw new RemoteToolError('remote/upload-response')
          const result = nativeValue(await response.json() as RemoteResult<FileUploadValue>)
          if (!result.receiptId || !result.file) throw new RemoteToolError('remote/upload-receipt')
          return result
        }, [source]) } finally { bytes.fill(0) }
      }, review, [source])
    }
    if (args.fileHandle !== undefined || args.name !== undefined || !['list', 'stat', 'read', 'download'].includes(action)) throw new RemoteToolError('remote/invalid-arguments')
    const path = text(args.path, 4096)
    if (!posix.isAbsolute(path) || posix.normalize(path) !== path || path.includes('\\') || /[\x00-\x1f\x7f]/.test(path)) throw new RemoteToolError('remote/path-policy-unavailable')
    const authority = target.allowedPaths.find(value => value.path === path && (value.actions as readonly string[]).includes(action))
    if (!authority) throw new RemoteToolError('remote/path-policy-unavailable')
    return this.connected(target, exec, async (client, signal) => {
      if (action === 'list') {
        const root = target.workspaces.find(value => value.workspaceId === binding.workspaceId)?.canonicalRoot
        if (!root) throw new RemoteToolError('remote/workspace-denied')
        const relative = posix.relative(root, path)
        if (relative === '..' || relative.startsWith('../') || posix.isAbsolute(relative)) throw new RemoteToolError('remote/path-policy-unavailable')
        const value = nativeValue(await client.remote.workspaceFiles.list(sessionId, relative || '.', signal))
        if (value.entries.length > 200 || value.path !== relative) throw new RemoteToolError('remote/list-budget')
        return { targetId: target.id, state: 'observed', data: value }
      }
      const stat = nativeValue(await client.remote.workspaceFiles.stat(sessionId, path, signal))
      if (stat.absolutePath !== path || (stat.bytes !== undefined && stat.bytes > authority.maxBytes)) throw new RemoteToolError('remote/path-policy-unavailable')
      if (action === 'stat') return { targetId: target.id, state: 'observed', data: stat }
      if (action === 'read') {
        const value = nativeValue(await client.remote.workspaceFiles.read(sessionId, path, { offset: 1, limit: 200 }, signal))
        if (value.absolutePath !== path || Buffer.byteLength(value.text) > Math.min(authority.maxBytes, 256 * 1024)) throw new RemoteToolError('remote/file-size')
        return { targetId: target.id, state: 'observed', data: value }
      }
      const response = await client.fetch(`/api/file?path=${encodeURIComponent(path)}`, { method: 'GET', signal })
      const staged = await this.spool.stage(response, { ownerSha256: target.ownerSha256, sourceTargetId: target.id, sourceTargetEpoch: target.authorizationEpoch, sourceInstallationId: target.descriptor.installationId, sourceRuntimeId: target.descriptor.runtimeId, sessionId, path, name: posix.basename(path) }, authority.maxBytes, signal)
      try { return await this.mutation(target, exec, 'files_download', { sessionId, path, bytes: staged.file.bytes, sha256: staged.file.sha256, fileHandle: staged.file.handle }, async () => staged.publish(exec.signal), { source: staged.file, exactConfiguredPathRisk: 'This exact absolute path is approved. Source path filters are not a remote symlink sandbox.', destination: 'private source spool only; explicit export required' }) }
      finally { await staged.discard() }
    })
  }
}

/** Optional services do not become root plugin injections when the feature is disabled. */
export async function installRemoteTools(ctx: Context, options: RemoteStoreOptions): Promise<RemoteToolsRuntime> {
  const runtime = new RemoteToolsRuntime(ctx, options)
  ctx.inject(['tools', 'approval', 'agents'], scope => runtime.register(scope))
  registerRemoteToolsUI(ctx, runtime)
  ctx.effect(() => () => runtime.close())
  return runtime
}
