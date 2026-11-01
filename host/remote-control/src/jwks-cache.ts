export interface JwksFetchTime {
  startedWall: number; receivedWall: number; readyWall: number
  startedMono: number; receivedMono: number; readyMono: number
}

const uint = (raw: string): bigint | undefined => {
  if (!/^\d{1,20}$/.test(raw)) return undefined
  const value = BigInt(raw)
  return value <= 18446744073709551615n ? value : undefined
}

/** Remaining reusable lifetime only. A just-fetched, verified set may serve the
 * current lookup even with zero TTL; it must not authorize subsequent lookups. */
export function jwksCacheTTL(headers: Headers, time: JwksFetchTime): number {
  let seconds = 60n, seen = false
  for (const part of (headers.get('cache-control') ?? '').split(',')) {
    const at = part.indexOf('='), name = (at < 0 ? part : part.slice(0, at)).trim().toLowerCase()
    if (name === 'no-cache' || name === 'no-store') return 0
    if (name !== 'max-age') continue
    if (seen || at < 0) return 0
    seen = true
    let raw = part.slice(at + 1).trim()
    if (raw.startsWith('"') && raw.endsWith('"') && raw.length >= 2) raw = raw.slice(1, -1)
    const value = uint(raw)
    if (value === undefined) return 0
    if (value < seconds) seconds = value
  }
  const lifetime = Number(seconds) * 1000
  if (!lifetime || Object.values(time).some(value => !Number.isFinite(value)) ||
      time.receivedWall < time.startedWall || time.readyWall < time.receivedWall ||
      time.receivedMono < time.startedMono || time.readyMono < time.receivedMono) return 0
  let age = 0
  const ageHeader = headers.get('age')
  if (ageHeader !== null) {
    const value = uint(ageHeader)
    if (value === undefined || value >= seconds) return 0
    age = Number(value) * 1000
  }
  let initial = age + time.receivedMono - time.startedMono
  const date = headers.get('date')
  if (date !== null) {
    const value = Date.parse(date)
    if (!Number.isFinite(value) || new Date(value).toUTCString() !== date) return 0
    initial = Math.max(initial, time.receivedWall - value)
  }
  return Math.max(0, lifetime - initial - (time.readyMono - time.receivedMono))
}
