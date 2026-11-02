import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'
import type { TestContext } from 'node:test'
import { hostPackageEntry } from './host-package-entry.js'

type ModuleName = 'command-journal' | 'linux-secret-service' | 'approval-lease' | 'signed-approvals'

/** Source tests remain build-independent. Explicit bundle selection verifies
 * before importing runtime code and never falls back to a source constructor. */
export async function hostPackageModule<T>(t: Pick<TestContext, 'after' | 'diagnostic'>, name: ModuleName): Promise<{ module: T; url: URL }> {
  assert.ok(['command-journal', 'linux-secret-service', 'approval-lease', 'signed-approvals'].includes(name))
  const bundled = process.env.REMOTE_TEST_HOST_BUNDLE_DIRECTORY !== undefined || process.env.REMOTE_TEST_HOST_BUNDLE_MANIFEST_SHA256 !== undefined
  const url = bundled
    ? new URL(`./${name}.js`, pathToFileURL(await hostPackageEntry(t)))
    : new URL(`../../host/remote-control/src/${name}.ts`, import.meta.url)
  return { module: await import(url.href) as T, url }
}
