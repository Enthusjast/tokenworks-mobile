import { createHash } from 'node:crypto'
import { TextDecoder } from 'node:util'

export const WIRE_FRAME_BYTES = 128 * 1024
export const WIRE_LOGICAL_BYTES = 2 * 1024 * 1024
export const WIRE_QUEUE_BYTES = 4 * 1024 * 1024
export const WIRE_QUEUE_COUNT = 256
const CHUNK_BYTES = 64 * 1024

/** Carrier framing only. Reassembled text is the complete, unchanged native frame. */
export class WireError extends Error {
  constructor(readonly code: string) { super(code) }
}
export class WireEncoder {
  #id = 0;
  *encode(text: string): Generator<string> {
    const bytes = Buffer.from(text)
    if (bytes.length > WIRE_LOGICAL_BYTES) throw new WireError('wire/logical-limit')
    if (bytes.length <= WIRE_FRAME_BYTES) { yield text; return }
    const id = String(++this.#id)
    const count = Math.ceil(bytes.length / CHUNK_BYTES)
    const sha256 = createHash('sha256').update(bytes).digest('hex')
    for (let index = 0; index < count; index++) {
      yield JSON.stringify({ type: 'wire.fragment', id, index, count, bytes: bytes.length, sha256,
        data: bytes.subarray(index * CHUNK_BYTES, (index + 1) * CHUNK_BYTES).toString('base64') })
    }
  }
}

export class WireDecoder {
  #id = 0
  #closed = false
  #pending: { id: string; count: number; bytes: number; sha256: string; payload: Buffer; index: number; size: number } | undefined
  #timer: NodeJS.Timeout | undefined
  constructor(private readonly failure: (error: WireError) => void, private readonly timeoutMs = 30_000) {
    if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new RangeError('invalid wire timeout')
  }
  get bufferedBytes(): number { return this.#pending?.bytes ?? 0 }
  #clear(): void { clearTimeout(this.#timer); this.#timer = undefined; this.#pending = undefined }
  close(): void { this.#closed = true; this.#clear() }
  decode(text: string): string | undefined {
    try {
      if (this.#closed) throw new WireError('wire/closed')
      if (Buffer.byteLength(text) > WIRE_FRAME_BYTES) throw new WireError('wire/frame-limit')
      const value = JSON.parse(text) as Record<string, unknown>
      if (value?.type !== 'wire.fragment') {
        if (this.#pending) throw new WireError('wire/interrupted')
        return text
      }
      if (Object.keys(value).sort().join(',') !== 'bytes,count,data,id,index,sha256,type' ||
        typeof value.id !== 'string' || typeof value.data !== 'string' || typeof value.sha256 !== 'string' ||
        !/^[a-f0-9]{64}$/.test(value.sha256) || !Number.isInteger(value.index) || !Number.isInteger(value.count) ||
        !Number.isInteger(value.bytes)) throw new WireError('wire/invalid-fragment')
      const { id, data, sha256 } = value
      const index = value.index as number, count = value.count as number, bytes = value.bytes as number
      if (bytes <= WIRE_FRAME_BYTES || bytes > WIRE_LOGICAL_BYTES || count !== Math.ceil(bytes / CHUNK_BYTES) ||
        index < 0 || index >= count || data.length > 4 * Math.ceil(CHUNK_BYTES / 3) ||
        !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(data)) throw new WireError('wire/invalid-fragment')
      if (!this.#pending) {
        if (index !== 0 || id !== String(this.#id + 1)) throw new WireError('wire/fragment-order')
        this.#pending = { id, count, bytes, sha256, payload: Buffer.allocUnsafe(bytes), index: 0, size: 0 }
        this.#timer = setTimeout(() => { this.close(); this.failure(new WireError('wire/fragment-timeout')) }, this.timeoutMs)
        this.#timer.unref()
      }
      const pending = this.#pending
      if (pending.id !== id || pending.count !== count || pending.bytes !== bytes || pending.sha256 !== sha256 ||
        index !== pending.index) throw new WireError('wire/fragment-order')
      const part = Buffer.from(data, 'base64')
      if (part.toString('base64') !== data || part.length !== Math.min(CHUNK_BYTES, bytes - index * CHUNK_BYTES)) throw new WireError('wire/invalid-fragment')
      part.copy(pending.payload, pending.size); pending.size += part.length; pending.index++
      if (pending.index !== count) return undefined
      const payload = pending.payload
      if (createHash('sha256').update(payload).digest('hex') !== sha256) throw new WireError('wire/checksum')
      const decoded = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(payload)
      if (JSON.parse(decoded)?.type === 'wire.fragment') throw new WireError('wire/nested-fragment')
      this.#id++; this.#clear()
      return decoded
    } catch (error) {
      this.close()
      throw error instanceof WireError ? error : new WireError('wire/invalid-frame')
    }
  }
}
