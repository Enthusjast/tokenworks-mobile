import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { z } from 'zod'
import { hostObservationsFrame, NativeObservationWindow } from '../host/remote-control/src/native-observations.js'

function plain(value: unknown): any {
  if (Array.isArray(value)) return value.map(plain)
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).filter(([key]) => key !== '$schema' && key !== 'format').map(([key, item]) => [key, plain(item)]))
  return value
}
const definition = plain(z.toJSONSchema(hostObservationsFrame, { target: 'draft-2020-12' }))
const resultRule = (operations: string[], results: string[]) => ({
  if: { required: ['operation'], properties: { operation: { enum: operations } } },
  then: { properties: { result: { enum: results } } },
})
definition.properties.payload.properties.series.items.allOf = [
  resultRule(['checkpoint', 'cold_checkpoint'], ['persisted', 'failed', 'unavailable']),
  resultRule(['prompt', 'create', 'prompt_reconcile'], ['ok', 'error', 'cancelled']),
  resultRule(['journal_command', 'journal_approval'], ['persisted', 'failed']),
]

definition.description = 'Optional host.observations.v1, installation-only, current connection/epoch. Journal cells additionally require host.journal.observations.v1. Self-reported fixed operations; never authority or command acceptance. Runtime validators also check unique cells, histogram arithmetic and monotone counter deltas.'
const path = new URL('../contracts/ws/v1/relay.schema.json', import.meta.url)
let text = await readFile(path, 'utf8')
const root = JSON.parse(text), name = 'hostObservations', ref = `#/$defs/${name}`
if (JSON.stringify(root.$defs[name]) !== JSON.stringify(definition) || !root.oneOf.some((item: any) => item.$ref === ref)) {
  if (process.argv.includes('--check')) { console.error('Relay Host observations schema is stale; run npm run contracts'); process.exitCode = 1 }
  else {
    const entry = `    "${name}": ` + JSON.stringify(definition, null, 2).replaceAll('\n', '\n    ')
    const pattern = new RegExp(`^    "${name}": \\{[\\s\\S]*?^    \\},?`, 'm')
    text = pattern.test(text) ? text.replace(pattern, () => entry + ',') : text.replace('  "$defs": {', '  "$defs": {\n' + entry + ',')
    if (!root.oneOf.some((item: any) => item.$ref === ref)) text = text.replace('  "oneOf": [', '  "oneOf": [\n    ' + JSON.stringify({ $ref: ref }) + ',')
    await writeFile(path, text)
  }
}
const identity = { runtime_id: '33333333-3333-4333-8333-333333333333', runtime_epoch: '44444444-4444-4444-8444-444444444444' }
const window = new NativeObservationWindow(), connection = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
window.record('checkpoint', 'persisted', 1500)
const first = { v: 1, type: 'host.observations', ...identity, payload: window.snapshot(connection) }
window.record('checkpoint', 'failed', 2000); window.record('prompt', 'ok', 200)
const next = { ...first, payload: window.snapshot(connection) }
window.record('journal_command', 'persisted', 7000); window.record('journal_approval', 'failed', 12000)
const journal = { ...first, payload: window.snapshot(connection) }
for (const [name, value] of [['host-observations', first], ['host-observations-next', next], ['host-observations-journal', journal]] as const) {
  hostObservationsFrame.parse(value)
  const path = new URL(`../contracts/ws/v1/golden/${name}.json`, import.meta.url), body = JSON.stringify(value, null, 2) + '\n'
  if (process.argv.includes('--check')) { if (await readFile(path, 'utf8').catch(() => '') !== body) { console.error(`${name} golden is stale`); process.exitCode = 1 } }
  else { await mkdir(new URL('../contracts/ws/v1/golden/', import.meta.url), { recursive: true }); await writeFile(path, body) }
}

// Flow counters negotiate independently of duration observations.
const { hostFlowFrame, flowPairs, FlowWindow } = await import('../host/remote-control/src/flow-observations.js')
const flowDefinition = plain(z.toJSONSchema(hostFlowFrame, { target: 'draft-2020-12' }))
flowDefinition.properties.payload.properties.cells.items.allOf = Object.entries(flowPairs).map(([stage, kinds]) => ({
  if: { required: ['stage'], properties: { stage: { const: stage } } }, then: { properties: { kind: { enum: kinds } } },
}))
flowDefinition.description = 'Optional installation-only host.flow.observations.v1. Counts Agent publications, per-consumer live follow input and created projection envelopes; not delivery, unique tokens, authority or a coalescing ratio. Runtime checks also enforce unique cells and monotone connection deltas.'
const flowPath = new URL('../contracts/ws/v1/relay.schema.json', import.meta.url)
let flowText = await readFile(flowPath, 'utf8')
const flowRoot = JSON.parse(flowText), flowName = 'hostFlowObservations', flowRef = `#/$defs/${flowName}`
if (JSON.stringify(flowRoot.$defs[flowName]) !== JSON.stringify(flowDefinition) || !flowRoot.oneOf.some((item: any) => item.$ref === flowRef)) {
  if (process.argv.includes('--check')) { console.error('Relay Host flow schema is stale; run npm run contracts'); process.exitCode = 1 }
  else {
    const entry = `    "${flowName}": ` + JSON.stringify(flowDefinition, null, 2).replaceAll('\n', '\n    ')
    const pattern = new RegExp(`^    "${flowName}": \\{[\\s\\S]*?^    \\},?`, 'm')
    flowText = pattern.test(flowText) ? flowText.replace(pattern, () => entry + ',') : flowText.replace('  "$defs": {', '  "$defs": {\n' + entry + ',')
    if (!flowRoot.oneOf.some((item: any) => item.$ref === flowRef)) flowText = flowText.replace('  "oneOf": [', '  "oneOf": [\n    ' + JSON.stringify({ $ref: flowRef }) + ',')
    await writeFile(flowPath, flowText)
  }
}
const flow = new FlowWindow()
flow.record('agent', 'start'); flow.record('agent', 'chunk'); flow.record('follow', 'snapshot'); flow.record('projection', 'snapshot')
const flowFirst = { v: 1, type: 'host.flow.observations', ...identity, payload: flow.snapshot(connection) }
flow.record('agent', 'chunk'); flow.record('follow', 'chunk'); flow.record('projection', 'chunk'); flow.record('projection', 'checkpoint')
const flowNext = { ...flowFirst, payload: flow.snapshot(connection) }
for (const [name, value] of [['host-flow-observations', flowFirst], ['host-flow-observations-next', flowNext]] as const) {
  hostFlowFrame.parse(value)
  const path = new URL(`../contracts/ws/v1/golden/${name}.json`, import.meta.url), body = JSON.stringify(value, null, 2) + '\n'
  if (process.argv.includes('--check')) { if (await readFile(path, 'utf8').catch(() => '') !== body) { console.error(`${name} golden is stale`); process.exitCode = 1 } }
  else await writeFile(path, body)
}
