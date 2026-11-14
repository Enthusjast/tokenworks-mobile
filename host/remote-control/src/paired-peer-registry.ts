import { randomUUID } from 'node:crypto'
import type { HostConnectionHandle } from '@deepseek-ai/dsh-client-connection'
import type { PeerScope } from '@deepseek-ai/dsh-typert-protocol'
import type { LanPairedDevice } from './lan-pairing-store.js'
interface PairedScope { binding: string; peer?: PeerScope; generation: string }

/** Stable local authorization generation; a control connection creates no native Peer. */
export class PairedPeerRegistry {
  readonly #entries = new Map<string, PairedScope>()
  constructor(readonly connection: HostConnectionHandle) {}
  #entry(record: LanPairedDevice): PairedScope {
    const binding = `${record.bindingSha256}:${record.authorizationEpoch}`
    let current = this.#entries.get(record.deviceId)
    if (current && (current.binding !== binding || current.peer?.signal.aborted)) { this.invalidate(record.deviceId); current = undefined }
    if (!current) {
      if (this.#entries.size >= 32 || record.localAuthorization !== 'owner') throw new Error('paired peer refused')
      current = { binding, generation: randomUUID() }; this.#entries.set(record.deviceId, current)
    }
    return current
  }
  generation(record: LanPairedDevice): string { return this.#entry(record).generation }
  acquire(record: LanPairedDevice): { peer: PeerScope; generation: string } {
    const current = this.#entry(record)
    current.peer ??= this.connection.createPeer({ principal: Object.freeze({ kind: 'owner' }), interactions: 'answer' })
    return { peer: current.peer, generation: current.generation }
  }
  invalidate(pairId: string): void { const current = this.#entries.get(pairId); this.#entries.delete(pairId); void current?.peer?.dispose().catch(() => {}) }
  close(): void { for (const id of this.#entries.keys()) this.invalidate(id) }
}
