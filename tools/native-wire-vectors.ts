import { parseRemoteStreamClientMessage, parseRemoteStreamServerMessage, parseRemoteEventResult } from '@deepseek-ai/dsh-api-gateway/stream-protocol'

export type WireVector = { id: string; side: 'client' | 'server' | 'event-result'; value: unknown; accepted: boolean; expected?: unknown }
export function buildWireVectors(): { source: string; vectors: WireVector[] } {
  const id = 'fixture-stream'
  const valid: Record<WireVector['side'], unknown[]> = {
    client: [
      { type: 'open', streamId: id, endpoint: 'session/follow', payload: { args: { request: { sessionId: 'fixture-session' } } } },
      { type: 'item', streamId: id }, { type: 'item', streamId: id, value: null },
      { type: 'item', streamId: id, value: { fixture: ['原样🙂', null] } },
      { type: 'end', streamId: id }, { type: 'cancel', streamId: id },
    ],
    server: [
      { type: 'item', streamId: id }, { type: 'item', streamId: id, value: null },
      { type: 'item', streamId: id, value: { fixture: ['原样🙂', null] } },
      { type: 'end', streamId: id },
      { type: 'error', streamId: id, error: { code: 'fixture/failure', message: 'isolated fixture', details: {} } },
    ],
    'event-result': [
      { clientId: 'fixture-client', eventId: 'fixture-event', outcome: { kind: 'next' } },
      { clientId: 'fixture-client', eventId: 'fixture-event', outcome: { kind: 'result' } },
      { clientId: 'fixture-client', eventId: 'fixture-event', outcome: { kind: 'result', value: null } },
      { clientId: 'fixture-client', eventId: 'fixture-event', outcome: { kind: 'result', value: { fixture: true } } },
      { clientId: 'fixture-client', eventId: 'fixture-event', outcome: { kind: 'rejected', error: { name: 'FixtureError', message: 'isolated fixture' } } },
      { clientId: 'fixture-client', eventId: 'fixture-event', outcome: { kind: 'rejected', error: { name: 'FixtureError', message: 'isolated fixture', code: 'fixture/failure', details: null } } },
    ],
  }
  const vectors: WireVector[] = []
  for (const side of ['client', 'server', 'event-result'] as const) {
    const positive = valid[side]
    const negative = [null, {}, { type: 'future', streamId: id }, ...positive.flatMap(value => {
      const fields = value as Record<string, unknown>
      return [
        { ...fields, unknownFixtureField: true },
        ...Object.keys(fields).flatMap(key => {
          const missing = { ...fields }; delete missing[key]
          return [missing, { ...fields, [key]: false }]
        }),
        ...(side === 'event-result' ? [{ ...fields, outcome: { kind: 'future' } }] : [{ ...fields, streamId: '' }]),
      ]
    })]
    const candidates = [...new Map([...positive, ...negative].map(value => [JSON.stringify(value), value])).values()]
    for (const [index, value] of candidates.entries()) {
      const vector: WireVector = { id: `${side}:${index}`, side, value, accepted: false }
      try {
        vector.expected = side === 'client' ? parseRemoteStreamClientMessage(JSON.stringify(value))
          : side === 'server' ? parseRemoteStreamServerMessage(JSON.stringify(value)) : parseRemoteEventResult(value)
        vector.accepted = true
      } catch (error) {
        if (!(error instanceof Error) || !error.message.startsWith('api gateway:')) throw error
      }
      vectors.push(vector)
    }
  }
  return { source: 'protocol-generated; validated by installed native wire parsers, not recorded execution', vectors }
}
