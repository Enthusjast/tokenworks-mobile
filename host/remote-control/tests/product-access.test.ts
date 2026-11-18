import assert from 'node:assert/strict'
import test from 'node:test'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import { API_REMOTE_FORWARDED_EVENTS } from '@deepseek-ai/dsh-api-remotes'
import { Context } from '@deepseek-ai/cordis'
import { apply } from '../src/index.js'
import { nativeMethods, installedNativeMethods, nativeCodec } from '../../../tools/native-contract.js'
import { createIsolatedNativeAssembly } from '../../../tools/capture-native-golden.js'
import { assertProductAccessContract, productAccessOutputs, readProductAccessContract } from '../../../tools/product-access-contract.js'
import { checkTypertContracts } from '../../../tools/typert-contract-check.js'
import {
  PRODUCT_NATIVE_VERSION, PRODUCT_NATIVE_VERSION_RANGE, isSupportedDshVersion,
  PRODUCT_OWNER_BINDINGS, productActions, isProductAction, isProductResource, isProductBinding,
  productActionAllowsResource, productActionAllowsGlobal, isProductOwnerEligible, productInteractionPolicy,
  type ProductBinding,
} from '../src/product-access.generated.js'

const owner = { role: 'owner', is_installation_owner: true, bindings: PRODUCT_OWNER_BINDINGS }

test('native version admission accepts the complete 0.2.2 SemVer family and rejects neighboring or malformed versions', () => {
  assert.equal(PRODUCT_NATIVE_VERSION, '0.2.2-rc.1')
  assert.equal(PRODUCT_NATIVE_VERSION_RANGE, '>=0.2.2-0 <0.2.3-0')
  for (const version of ['0.2.2-0', '0.2.2-alpha.0', '0.2.2-rc.1', '0.2.2-rc.2', '0.2.2', '0.2.2+build.01', '0.2.2-rc.2+build.01']) {
    assert.equal(isSupportedDshVersion(version), true, version)
  }
  for (const version of [undefined, null, 20202, {}, '0.2.1', '0.2.1-rc.5', '0.2.3-0', '0.2.3-rc.1', '0.2.3',
    'v0.2.2', '0.2.2 ', ' 0.2.2', '0.2.2\n', '00.2.2', '0.02.2', '0.2.02', '0.2.2-', '0.2.2-01', '0.2.2-rc.01',
    '0.2.2-rc..1', '0.2.2+', '0.2.2+build..1', '0.2.2-rc_1']) {
    assert.equal(isSupportedDshVersion(version), false, JSON.stringify(version))
  }
})

test('enabled plugin admission validates the family before acquiring credentials or installing transports', async () => {
  const ctx = new Context()
  try {
    for (const dshVersion of ['', '0.2.1-rc.5', '0.2.3-rc.1', '0.2.2-rc.01']) {
      await assert.rejects(apply(ctx, { enabled: true, dshVersion }), /unsupported dsh version/)
    }
    for (const dshVersion of ['0.2.2-rc.1', '0.2.2-rc.2', '0.2.2', '0.2.2-rc.2+build.7']) {
      await assert.rejects(apply(ctx, { enabled: true, dshVersion }), /authenticated Relay and stable runtime identity required/)
    }
  } finally { await ctx.fiber.dispose() }
})

test('one canonical matrix covers the exact installed inventory, selected codecs and every owner action/resource pair', async () => {
  const contract = await assertProductAccessContract(await readProductAccessContract())
  assert.deepEqual(Object.entries(productActions).filter(([, policy]) => policy.selected).map(([action]) => action), nativeMethods.map(([action]) => action))
  assert.equal(nativeMethods.length, 40)
  assert.deepEqual(Object.keys(contract.actions).filter(action => !action.startsWith('event:')), installedNativeMethods.map(([action]) => action))
  for (const [action, policy] of Object.entries(contract.actions)) {
    assert.equal(productActions[action as keyof typeof productActions].capability, policy.capability)
    for (const kind of ['global', 'session', 'workspace', 'terminal', 'unknown']) {
      assert.equal(productActionAllowsResource(action, kind), policy.resources.includes(kind as 'global'))
      assert.equal(PRODUCT_OWNER_BINDINGS.some(binding => binding.resource.kind === kind && binding.actions.includes(action as keyof typeof productActions)), policy.resources.includes(kind as 'global'))
    }
  }
})

test('compatibility fails closed on versions, unknown/removed native methods, selected methods and descriptor/resource-reader drift', async () => {
  const original = await readProductAccessContract()
  for (const change of [
    (value: typeof original) => { value.dshVersion = '0.2.1-rc.6' },
    (value: typeof original) => { value.actions['terminal/unknown'] = structuredClone(value.actions['terminal/attach']!) },
    (value: typeof original) => { delete value.actions['terminal/attach'] },
    (value: typeof original) => { value.actions['terminal/attach']!.selected = false },
    (value: typeof original) => { value.actions['terminal/attach']!.descriptorSha256 = '0'.repeat(64) },
    (value: typeof original) => { value.nativeResourceSources[Object.keys(value.nativeResourceSources)[0]!] = '0'.repeat(64) },
    (value: typeof original) => { value.nativeForwardedEventsSha256 = '0'.repeat(64) },
    (value: typeof original) => { value.actions['event:api-session/status']!.resources = ['global'] },
    (value: typeof original) => { value.actions['event:api-session/activity']!.eventDelivery = 'owner-waterfall' },
    (value: typeof original) => { value.actions['event:approval/decided']!.eventDelivery = 'emit' },
  ]) {
    const value = structuredClone(original)
    change(value)
    await assert.rejects(assertProductAccessContract(value), /version|drift/)
  }
})

test('native status/activity events use their actual session readers while approval decided remains a retained non-forwarded capability', () => {
  for (const entry of API_REMOTE_FORWARDED_EVENTS) {
    if (entry.event !== 'api-session/status' && entry.event !== 'api-session/activity') continue
    assert.equal(entry.mode, 'emit')
    assert.equal(entry.resources.session('session-1' as never), 'session-1')
    assert.equal(productActions[`event:${entry.event}`].eventDelivery, 'emit')
    assert.equal(productActionAllowsResource(`event:${entry.event}`, 'session'), true)
    assert.equal(productActionAllowsResource(`event:${entry.event}`, 'global'), false)
  }
  assert.equal(API_REMOTE_FORWARDED_EVENTS.some(entry => String(entry.event) === 'approval/decided'), false)
  assert.equal(productActions['event:approval/decided'].eventDelivery, 'not-forwarded')
  assert.equal(productActions['event:approval/request'].eventDelivery, 'owner-waterfall')
  assert.equal(productActions['event:user-questions/request'].eventDelivery, 'owner-waterfall')
})

test('generated TS, Kotlin and Go contract checks accept CRLF and reject missing/stale/BOM output', async () => {
  const outputs = await productAccessOutputs()
  const root = await mkdtemp(join(tmpdir(), 'product access-#中文-'))
  try {
    for (const output of outputs) {
      await mkdir(dirname(join(root, output.path)), { recursive: true })
      await writeFile(join(root, output.path), output.content.replaceAll('\n', '\r\n'))
    }
    await checkTypertContracts(outputs, root)
    for (const output of outputs) {
      for (const content of ['', output.content + '\n', '\ufeff' + output.content]) {
        await writeFile(join(root, output.path), content)
        await assert.rejects(checkTypertContracts([output], root), /is stale/)
      }
      await rm(join(root, output.path))
      await assert.rejects(checkTypertContracts([output], root), /is stale/)
    }
  } finally { await rm(root, { recursive: true, force: true }) }
})

test('terminal attach and all agent lookups are session scoped, not blanket global grants', async () => {
  const require = createRequire(import.meta.url)
  const accessPath = join(dirname(require.resolve('@deepseek-ai/dsh-api-gateway/package.json')), 'lib/types/access.js')
  // The installed resolver is deliberately not a package export. Resolving its
  // private module tests the exact pinned installed-module boundary, not a copy.
  const { callResources } = await import(pathToFileURL(accessPath).href)
  // Run the installed Gateway resolver; these registry declarations are exactly
  // the installed dsh-agent and controller resource registrations pinned above.
  const registry = {
    resources: { entries: () => [{ kind: 'session', field: 'sessionId' }, { kind: 'workspace', field: 'workspaceId' }] },
    lookups: { get: (name: string) => name === 'agent' || name === 'session' ? { resource: 'session' } : undefined },
    contexts: { getHost: () => ({ resource: 'session' }) },
  }
  for (const [action, descriptor] of nativeMethods.filter(([name]) => name.startsWith('terminal/'))) {
    const values: Record<string, unknown> = { agentId: 'session-1', sessionId: 'session-1', id: 'term-1', title: 'terminal', request: { id: 'term-1', cols: 80, rows: 24 } }
    const args = Object.fromEntries(descriptor.parameters.map(parameter => [parameter.wire, values[parameter.wire]]))
    const validated = nativeCodec(action, 'input').parse(args)
    assert.deepEqual(callResources(registry, undefined, descriptor, validated, new AbortController().signal), [{ kind: 'session', id: 'session-1' }])
    assert.equal(productActionAllowsResource(action, 'session'), true)
    assert.equal(productActionAllowsResource(action, 'global'), false)
    assert.equal(productActionAllowsGlobal(action), false)
  }
  assert.equal(isProductBinding({ resource: { kind: 'session', id: 'session-1' }, actions: ['terminal/attach'] }), true)
  assert.equal(isProductBinding({ resource: { kind: 'global' }, actions: ['terminal/attach'] }), false)
  assert.equal(productActionAllowsGlobal('session/list'), true)
  assert.equal(productActionAllowsGlobal('session/prompt'), false)
  assert.equal(productActionAllowsResource('workspace/archiveSession', 'session'), true)
  assert.equal(productActionAllowsResource('workspace/archiveSession', 'workspace'), false)
  assert.equal(productActionAllowsResource('workspace/insertSessionBefore', 'workspace'), true)
  assert.equal(productActionAllowsResource('workspace/insertSessionBefore', 'session'), true)
})

test('child history and discovery resolve the exact native session, not a global or parent-only grant', async () => {
  const root = await mkdtemp(join(tmpdir(), 'native-resource-scope-'))
  try {
    const { ctx, peer } = await createIsolatedNativeAssembly(root)
    try {
      const require = createRequire(import.meta.url)
      const accessPath = join(dirname(require.resolve('@deepseek-ai/dsh-api-gateway/package.json')), 'lib/types/access.js')
      // This pinned resolver is private to the installed gateway package, not an importable export.
      const { callResources } = await import(pathToFileURL(accessPath).href)
      const address = { kind: 'subagent', parentSessionId: 'parent', childSessionId: 'child', mode: 'continuable' }
      const requests = [
        ['session/follow', { request: { address } }],
        ['session/page', { request: { address } }],
        ['skills/list', { request: { sessionId: 'child' } }],
        ['fileReferences/list', { agentId: 'child', query: '' }],
      ] as const
      for (const [action, args] of requests) {
        const descriptor = nativeMethods.find(([name]) => name === action)![1]
        const validated = nativeCodec(action, 'input').parse(args)
        assert.deepEqual(callResources(ctx.typert, undefined, descriptor, validated, peer.signal), [{ kind: 'session', id: 'child' }])
        assert.equal(productActionAllowsGlobal(action), false)
      }
    } finally {
      try { await peer.dispose() } finally { await ctx.fiber.dispose() }
    }
  } finally { await rm(root, { recursive: true, force: true }) }
})

test('resource/action validation rejects unknown names, cross-kind grants and malformed resource identities', () => {
  for (const value of [null, '', 1, [], {}, { kind: 'global', id: 's1' }, { kind: 'session', id: '' }, { kind: 'workspace', id: null }, { kind: 'terminal' }, { kind: 'session', extra: true }]) assert.equal(isProductResource(value), false)
  for (const kind of ['global', 'session', 'workspace']) assert.equal(isProductResource({ kind }), true)
  for (const action of ['terminal/unknown', 'event:unknown', 'unknown/action', '__proto__', null]) assert.equal(isProductAction(action), false)
  for (const value of [null, {}, { resource: { kind: 'session' }, actions: ['unknown/action'] }, { resource: { kind: 'workspace' }, actions: ['session/prompt'] }, { resource: { kind: 'session' }, actions: ['terminal/attach'], extra: true }]) assert.equal(isProductBinding(value), false)
})

test('native owner eligibility requires actual ownership and the entire kind-wide canonical matrix, independent of answer transport participation', () => {
  assert.equal(isProductOwnerEligible(owner), true)
  assert.equal(isProductOwnerEligible({ ...owner, role: 'operator' }), false)
  assert.equal(isProductOwnerEligible({ ...owner, is_installation_owner: false }), false)
  assert.equal(isProductOwnerEligible({ ...owner, bindings: [] }), false)
  for (const removed of PRODUCT_OWNER_BINDINGS.flatMap(binding => binding.actions.map(action => ({ kind: binding.resource.kind, action })))) {
    const bindings = PRODUCT_OWNER_BINDINGS.map(binding => ({ resource: binding.resource, actions: binding.actions.filter(action => binding.resource.kind !== removed.kind || action !== removed.action) }))
    assert.equal(isProductOwnerEligible({ ...owner, bindings }), false, `${removed.kind}:${removed.action}`)
  }
  const concrete: ProductBinding[] = PRODUCT_OWNER_BINDINGS.map(binding => ({ ...binding, resource: binding.resource.kind === 'global' ? binding.resource : { ...binding.resource, id: 'only-one' } }))
  assert.equal(isProductOwnerEligible({ ...owner, bindings: concrete }), false)
  assert.deepEqual(productInteractionPolicy(owner, 'owner'), { participation: 'answer', answerAuthority: true })
  assert.deepEqual(productInteractionPolicy(owner, 'device'), { participation: 'answer', answerAuthority: false })
  assert.deepEqual(productInteractionPolicy({ ...owner, role: 'viewer' }, 'owner'), { participation: 'answer', answerAuthority: false })
})
