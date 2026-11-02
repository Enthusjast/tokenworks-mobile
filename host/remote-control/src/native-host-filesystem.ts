import type { Context } from '@deepseek-ai/cordis'
import type {} from '@deepseek-ai/cordis-plugin-loader'
import { LocalFileSystem } from '@deepseek-ai/dsh-fs-local'
import { SandboxedFileSystem } from '@deepseek-ai/dsh-fs-sandbox'

/** Exact native implementations with Host read semantics; never a name/shape test. */
export async function nativeHostFileSystem(ctx: Context, provider: object, signal: AbortSignal): Promise<boolean> {
  signal.throwIfAborted()
  const backend = Object.getPrototypeOf(provider)?.constructor
  if (backend === LocalFileSystem || backend === SandboxedFileSystem) return true
  // An installed CLI can own another copy of the same pinned packages. Resolve
  // only these fixed modules through its Loader, using the profile's runtime
  // resolution, then compare the actual exported constructor identity again.
  // Subclasses, policy wrappers and different execution worlds remain unknown.
  const loader = ctx.loader
  if (!loader) return false
  for (const [specifier, name] of [
    ['@deepseek-ai/dsh-fs-local', 'LocalFileSystem'],
    ['@deepseek-ai/dsh-fs-sandbox', 'SandboxedFileSystem'],
  ] as const) {
    let module: Record<string, unknown>
    try { module = await loader.import(specifier) }
    catch { signal.throwIfAborted(); continue }
    signal.throwIfAborted()
    if (typeof module?.[name] === 'function' && backend === module[name]) return true
  }
  return false
}
