/** Linked-checkout entry only; the release bundle never includes this fixture. */
export { Config, inject, name } from '../../tools/dev/fixture-host.mjs'

export async function apply(ctx, config) {
  // Keep mobile and HTTP fixture owners separate when attaching to an already
  // running dsh whose process environment cannot be changed by the supervisor.
  const fixture = config?.relayUrl === 'https://localhost:8443'
    ? await import('../../tools/dev/fixture-host.mjs?mobile=1')
    : await import('../../tools/dev/fixture-host.mjs')
  return fixture.apply(ctx, config)
}
