import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { Ajv2020 } from 'ajv/dist/2020.js'
import { FlowObservations, FlowWindow, bindFlowObservations, flowKind, flowPairs, hostFlowFrame } from '../src/flow-observations.js'
import { DshClient } from '../src/dsh-client.js'
import { SessionSync } from '../src/session-sync.js'
import { type Frame, parseCommand } from '../src/protocol.js'
import { host } from './harness.js'

test('flow reports have fixed cells, shared schema and detached snapshots without arbitrary text', async () => {
  const schema = JSON.parse(await readFile('contracts/ws/v1/relay.schema.json', 'utf8'))
  const ajv = new Ajv2020({ strict: true }); ajv.addFormat('date-time', value => Number.isFinite(Date.parse(value)))
  const validate = ajv.compile(schema)
  for (const name of ['host-flow-observations', 'host-flow-observations-next']) {
    const golden = JSON.parse(await readFile(`contracts/ws/v1/golden/${name}.json`, 'utf8'))
    assert.equal(hostFlowFrame.safeParse(golden).success, true)
    assert.equal(validate(golden), true)
    const malformed = structuredClone(golden); malformed.payload.cells[0].kind = 'question'
    assert.equal(hostFlowFrame.safeParse(malformed).success, false)
    assert.equal(validate(malformed), false)
  }
  const window = new FlowWindow(), identity = { connection_id: randomUUID() }
  for (const [stage, kinds] of Object.entries(flowPairs)) for (const kind of kinds) window.record(stage as never, kind)
  const first = window.snapshot(identity.connection_id)!
  assert.equal(first.cells.length, 20)
  window.record('agent', 'chunk'); window.record('PRIVATE_METRIC_PATH' as never, 'chunk')
  window.record('agent', 'question')
  const next = window.snapshot(identity.connection_id)!
  assert.equal(first.cells.find(cell => cell.stage === 'agent' && cell.kind === 'chunk')!.count, 1)
  assert.equal(next.cells.find(cell => cell.stage === 'agent' && cell.kind === 'chunk')!.count, 2)
  assert.equal(next.dropped, 2)
  assert.equal(JSON.stringify(next).includes('PRIVATE_METRIC_PATH'), false)
  assert.equal(flowKind('projection', 'question.timed.snapshot'), 'question')
  assert.equal(flowKind('agent', 'replace'), 'other')
  assert.equal(flowKind('follow', 'PRIVATE_USER_CONTENT'), 'other')
})

test('flow observers are best effort, rebound by connection and preserve send-time samples', () => {
  const source = new FlowObservations(), frames: any[] = [], identity = { connectionId: randomUUID(), runtimeId: randomUUID(), epoch: randomUUID() }
  source.record('agent', 'start')
  let attempts = 0
  const first = bindFlowObservations(source, identity, frame => {
    attempts++
    if (attempts === 1) throw new Error('PRIVATE_SEND_ERROR')
    if (attempts === 2) return false
    frames.push(frame)
    if (frames.length === 1) source.record('follow', 'snapshot')
    return true
  })
  try {
    source.record('agent', 'chunk'); first.flush(); first.flush(); first.flush(); first.flush()
    assert.equal(frames.length, 2)
    assert.equal(frames[0].payload.sequence, 3)
    assert.equal(frames[0].payload.cells.length, 1)
    assert.equal(frames[1].payload.cells.length, 2)
    const second = bindFlowObservations(source, { ...identity, connectionId: randomUUID() }, frame => { frames.push(frame); return true })
    try {
      first.close(); source.record('agent', 'end'); second.flush()
      assert.equal(frames.at(-1).payload.sequence, 1)
      assert.deepEqual(frames.at(-1).payload.cells, [{ stage: 'agent', kind: 'end', count: 1 }])
    } finally { second.close() }
    assert.equal(source.enabled, false)
    source.listen(() => { throw new Error('PRIVATE_OBSERVER_ERROR') })
    assert.doesNotThrow(() => source.record('agent', 'chunk'))
  } finally { first.close() }
})

test('real native Agent publications stay unique while two live consumers create their own projections', { timeout: 15_000 }, async () => {
  const h = await host({ probe: false })
  const client = new DshClient(h.ctx, h.runtimeId, [{ id: 'fixture', cwd: h.cwd }], ['fixture'])
  const window = new FlowWindow(), remove = client.flow.listen((stage, kind) => window.record(stage, kind)), observe = client.observeAgentFlow()
  const lifetime = new AbortController(), epoch = randomUUID(), sync = new SessionSync(client, epoch)
  const iterators: AsyncIterator<Frame>[] = []
  let disposeReference = () => {}
  try {
    const created = await client.create('fixture', 'fixture'), native = await client.resolve(created.session_id, lifetime.signal)
    const reference: string[] = []
    disposeReference = h.ctx.on('agent/assistant-stream', ({ agent, frame }) => { if (agent.session.id === native) reference.push(frame.type) }, { global: true })
    for (let i = 0; i < 2; i++) {
      const command = parseCommand({ v: 0, type: 'session.subscribe', runtime_epoch: epoch, request_id: randomUUID(), session_id: created.session_id, payload: { max_messages: 10 } })
      assert.equal(command.type, 'session.subscribe')
      const iterator = sync.follow(command as Extract<typeof command, { type: 'session.subscribe' }>, lifetime.signal)[Symbol.asyncIterator]()
      iterators.push(iterator)
      assert.equal((await iterator.next()).value?.type, 'session.snapshot')
      assert.equal((await iterator.next()).value?.type, 'session.checkpoint')
    }
    const received: string[] = []
    const readers = iterators.map(async iterator => {
      try {
        for (;;) {
          const item = await iterator.next()
          if (item.done) return
          received.push(item.value.type)
          if (item.value.type === 'assistant.end') return
        }
      } finally { await iterator.return?.() }
    })
    await client.prompt(native, randomUUID(), 'queue', 'PRIVATE_FLOW_PROMPT_BODY', lifetime.signal)
    await Promise.all(readers)
    const report = window.snapshot(randomUUID())!, count = (stage: string, kind: string) => report.cells.find(cell => cell.stage === stage && cell.kind === kind)?.count ?? 0
    for (const kind of ['start', 'chunk', 'end']) {
      assert.equal(count('agent', kind), reference.filter(type => type === kind).length)
      assert.equal(count('follow', kind), received.filter(type => type === `assistant.${kind}`).length)
      assert.equal(count('projection', kind), received.filter(type => type === `assistant.${kind}`).length)
    }
    assert.equal(count('agent', 'start'), 1)
    assert.equal(count('follow', 'start'), 2)
    assert.equal(count('projection', 'snapshot'), 2)
    assert.equal(count('projection', 'checkpoint') >= 2, true)
    const foreignCwd = join(h.directory, 'foreign'); await mkdir(foreignCwd)
    const foreign = await h.ctx.sessionController.create({ cwd: foreignCwd, agentPreset: 'fixture' })
    const ended = new Promise<void>(resolve => {
      const dispose = h.ctx.on('agent/assistant-stream', ({ agent, frame }) => { if (agent.session.id === foreign.sessionId && frame.type === 'end') { dispose(); resolve() } }, { global: true })
    })
    await h.ctx.sessionController.prompt({ sessionId: foreign.sessionId, requestId: `foreign:${randomUUID()}` as never, mode: 'queue', content: [{ type: 'text', text: 'PRIVATE_FOREIGN_BODY' }] }, lifetime.signal)
    await ended
    assert.deepEqual(window.snapshot(randomUUID())!.cells, report.cells)
    for (const value of [created.session_id, native, h.cwd, 'PRIVATE_FLOW_PROMPT_BODY', 'PRIVATE_FOREIGN_BODY']) assert.equal(JSON.stringify(report).includes(value), false)
    observe()
  } finally {
    lifetime.abort(); for (const iterator of iterators) await iterator.return?.()
    disposeReference(); observe(); remove(); await h.close()
  }
})
