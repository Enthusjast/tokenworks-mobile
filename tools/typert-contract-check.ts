import { readFile } from 'node:fs/promises'
import { sep } from 'node:path'
import { pathToFileURL } from 'node:url'

export type TypertContractCandidate = { path: string; content: string }

export const normalizeContractLines = (text: string): string => text.replaceAll('\r\n', '\n')

/**
 * Verifies generated contracts match disk content, comparing line-ending-normalized
 * text only: CRLF-vs-LF drift (e.g. Windows checkouts) is accepted, while stale
 * content, whitespace edits and BOM are rejected. Shared by the generator CLI
 * `--check` mode and regression tests.
 */
export async function checkTypertContracts(candidates: TypertContractCandidate[], root: string): Promise<void> {
  const base = pathToFileURL(root + sep)
  for (const { path, content } of candidates) {
    const expected = normalizeContractLines(content)
    const actual = await readFile(new URL(path, base), 'utf8').catch(() => '')
    if (normalizeContractLines(actual) !== expected) throw new Error(`${path} is stale; run npm run contracts:typert`)
  }
}
