import type { TypertClientRemote, RemoteResult } from '@deepseek-ai/dsh-typert-protocol'
import type { SessionId } from '@deepseek-ai/dsh-session/types'
import type { SessionWireEvent } from '@deepseek-ai/dsh-api-session-controller/types'
import '@deepseek-ai/dsh-api-session-controller/remote'

export class RemoteToolError extends Error {
  constructor(readonly code: string) { super(code); this.name = 'RemoteToolError' }
}
export function nativeValue<T>(result: RemoteResult<T>): T {
  if (!result.ok) throw new RemoteToolError(`remote/native/${result.error.code}`)
  return result.value
}
export interface PromptEvidence {
  requestId: string
  sessionId: string
  state: 'not-observed' | 'queued' | 'consumed' | 'turn-ended'
  consumedSeq?: number
  turn?: number
  terminalSeq?: number
  reason?: unknown
  incomplete: boolean
  evidenceSource: 'native-events-and-history'
}
const object = (value: unknown): Record<string, unknown> | undefined => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : undefined
const sourceMatches = (value: unknown, requestId: string): boolean => object(object(value)?.source)?.rpcId === requestId

/** Only a consumed exact rpcId enclosed by its native turn can acquire a terminal reason. */
export function correlatePrompt(events: readonly SessionWireEvent[], requestId: string, sessionId: string, queued = false, incomplete = false): PromptEvidence {
  const evidence: PromptEvidence = { requestId, sessionId, state: queued ? 'queued' : 'not-observed', incomplete, evidenceSource: 'native-events-and-history' }
  let activeTurn: number | undefined
  let previousSeq: number | undefined
  for (const event of events) {
    if (previousSeq !== undefined && event.seq !== previousSeq + 1) { activeTurn = undefined; evidence.incomplete = true }
    previousSeq = event.seq
    const data = object(event.data)
    if (event.type === 'turn/start' && Number.isSafeInteger(data?.turn)) activeTurn = data!.turn as number
    if (event.type === 'agent/inbox/spliced' && Array.isArray(data?.inserted) && data.inserted.some(message => sourceMatches(message, requestId)) && evidence.state === 'not-observed') evidence.state = 'queued'
    if (event.type === 'user/message' && sourceMatches(data, requestId)) {
      evidence.state = 'consumed'; evidence.consumedSeq = event.seq
      if (activeTurn === undefined) { delete evidence.turn; evidence.incomplete = true }
      else evidence.turn = activeTurn
    }
    if (event.type === 'turn/end') {
      if (evidence.state === 'consumed' && activeTurn !== undefined && evidence.turn === activeTurn && data?.turn === activeTurn && object(data.reason)?.kind) {
        evidence.state = 'turn-ended'; evidence.terminalSeq = event.seq; evidence.reason = data.reason
        return evidence
      }
      activeTurn = undefined
    }
  }
  return evidence
}

/** Subscribe before reading a fixed native event cut; never infer completion from idle state. */
export async function waitForRemotePrompt(remote: TypertClientRemote, sessionId: SessionId, requestId: string, signal: AbortSignal, timeoutMs = 120_000): Promise<PromptEvidence & { timedOut: boolean }> {
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 120_000) throw new RemoteToolError('remote/invalid-timeout')
  const deadline = AbortSignal.timeout(timeoutMs), combined = AbortSignal.any([signal, deadline])
  const address = { kind: 'session', sessionId } as const
  const handle = remote.session.observeEvent({ address }, combined)
  const events = new Map<number, SessionWireEvent>()
  let bytes = 0, queued = false, incomplete = false
  let evidence = correlatePrompt([], requestId, sessionId)
  const add = (event: SessionWireEvent) => {
    if (events.has(event.seq)) return
    bytes += Buffer.byteLength(JSON.stringify(event))
    if (bytes > 8 * 1024 * 1024 || events.size >= 8192) throw new RemoteToolError('remote/history-budget')
    events.set(event.seq, event)
  }
  const evaluate = () => {
    evidence = correlatePrompt([...events.values()].sort((a, b) => a.seq - b.seq), requestId, sessionId, queued, incomplete)
    return evidence.state === 'turn-ended'
  }
  try {
    for await (const frame of handle) {
      if (combined.aborted) break
      if (frame.type === 'ready') {
        const state = nativeValue(await remote.session.state({ sessionId }, combined))
        queued = state.queue?.some(item => item.rpcId === requestId) ?? false
        const opening = nativeValue(await remote.session.events({ address, maxMessages: 200, turnWindow: { minMessages: 1, minTurns: 1 } }, combined))
        for (const record of opening.records) add(record.event)
        let hasMore = opening.hasMore, pages = 0
        while (hasMore && pages++ < 8 && !evaluate()) {
          const beforeSeq = events.size ? Math.min(...events.keys()) : opening.cursor + 1
          const page = nativeValue(await remote.session.events({ address, throughSeq: opening.cursor, beforeSeq, maxMessages: 200, turnWindow: { minMessages: 1, minTurns: 1 } }, combined))
          if (!page.records.length) { incomplete = hasMore; break }
          for (const record of page.records) add(record.event)
          const next = Math.min(...events.keys())
          if (next >= beforeSeq) throw new RemoteToolError('remote/history-no-progress')
          hasMore = page.hasMore
        }
        incomplete ||= hasMore
      } else add(frame.event)
      if (evaluate()) return { ...evidence, timedOut: false }
    }
  } catch (error) {
    if (!deadline.aborted || signal.aborted) throw error
  } finally { handle.dispose() }
  if (signal.aborted) throw signal.reason
  evaluate()
  return { ...evidence, timedOut: deadline.aborted }
}
