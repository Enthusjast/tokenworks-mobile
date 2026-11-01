import { z } from 'zod'

export const HOST_FLOW_CAPABILITY = 'host.flow.observations.v1'
export const flowStages = ['agent', 'follow', 'projection'] as const
export const flowKinds = ['start', 'chunk', 'end', 'replace', 'snapshot', 'event', 'checkpoint', 'question', 'other'] as const
export type FlowStage = typeof flowStages[number]
export type FlowKind = typeof flowKinds[number]
const MAX_COUNT = 1_000_000_000
const uuid = z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
export const flowPairs = {
  agent: ['start', 'chunk', 'end', 'other'],
  follow: ['snapshot', 'event', 'start', 'chunk', 'replace', 'end', 'other'],
  projection: ['snapshot', 'event', 'checkpoint', 'start', 'chunk', 'replace', 'end', 'question', 'other'],
} as const
export function flowPair(stage: string, kind: string): boolean {
  return Object.hasOwn(flowPairs, stage) && (flowPairs[stage as FlowStage] as readonly string[]).includes(kind)
}
/** Only the public discriminant is inspected. No payload, identity or error is retained. */
export function flowKind(stage: FlowStage, type: string): FlowKind {
  const kind = type.startsWith('assistant.') ? type.slice(10) : type.startsWith('session.') ? type.slice(8)
    : type.startsWith('question.') ? 'question' : type
  return flowPair(stage, kind) ? kind as FlowKind : 'other'
}
const cell = z.strictObject({ stage: z.enum(flowStages), kind: z.enum(flowKinds), count: z.number().int().min(1).max(MAX_COUNT) })
  .refine(value => flowPair(value.stage, value.kind))
export const hostFlowPayload = z.strictObject({ connection_id: uuid, sequence: z.number().int().min(1).max(MAX_COUNT),
  dropped: z.number().int().min(0).max(MAX_COUNT), cells: z.array(cell).max(20),
}).refine(value => new Set(value.cells.map(item => `${item.stage}:${item.kind}`)).size === value.cells.length)
export const hostFlowFrame = z.strictObject({ v: z.literal(1), type: z.literal('host.flow.observations'),
  runtime_id: uuid, runtime_epoch: uuid, payload: hostFlowPayload })
export type FlowCell = z.infer<typeof cell>

/** One connection-scoped observer; disabled/disconnected operation retains nothing. */
export class FlowObservations {
  private listener: ((stage: FlowStage, kind: FlowKind) => void) | undefined
  get enabled(): boolean { return this.listener !== undefined }
  listen(listener: (stage: FlowStage, kind: FlowKind) => void): () => void {
    this.listener = listener
    return () => { if (this.listener === listener) this.listener = undefined }
  }
  record(stage: FlowStage, kind: FlowKind): void {
    try { this.listener?.(stage, kind) } catch { /* Metrics cannot change native execution. */ }
  }
}
export class FlowWindow {
  private readonly cells = new Map<string, FlowCell>()
  private dropped = 0
  private sequence = 0
  version = 0
  record(stage: FlowStage, kind: FlowKind): void {
    const key = `${stage}:${kind}`, previous = this.cells.get(key)
    if (!flowPair(stage, kind) || (previous?.count ?? 0) >= MAX_COUNT) {
      if (this.dropped < MAX_COUNT) { this.dropped++; this.version++ }
      return
    }
    this.cells.set(key, { stage, kind, count: (previous?.count ?? 0) + 1 }); this.version++
  }
  snapshot(connectionId: string): z.infer<typeof hostFlowPayload> | undefined {
    if (!this.version || this.sequence >= MAX_COUNT) return undefined
    return { connection_id: connectionId, sequence: ++this.sequence, dropped: this.dropped,
      cells: [...this.cells.values()].sort((a, b) => `${a.stage}:${a.kind}`.localeCompare(`${b.stage}:${b.kind}`)).map(item => ({ ...item })) }
  }
}
export function bindFlowObservations(source: FlowObservations, identity: { connectionId: string; runtimeId: string; epoch: string },
  send: (frame: z.infer<typeof hostFlowFrame>) => boolean) {
  const window = new FlowWindow()
  let sentVersion = 0, closed = false
  const dispose = source.listen((stage, kind) => window.record(stage, kind))
  const flush = () => {
    if (closed || window.version === sentVersion) return
    const version = window.version, payload = window.snapshot(identity.connectionId)
    if (!payload) return
    try {
      if (send({ v: 1, type: 'host.flow.observations', runtime_id: identity.runtimeId, runtime_epoch: identity.epoch, payload })) sentVersion = version
    } catch { /* Cumulative counters wait for the next best-effort report. */ }
  }
  const timer = setInterval(flush, 10_000); timer.unref()
  return { flush, close() { closed = true; clearInterval(timer); dispose() } }
}
