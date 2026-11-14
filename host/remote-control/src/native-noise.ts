import { createRequire } from 'node:module'

export const NOISE_SUITE = 'Noise_IK_25519_ChaChaPoly_SHA256'

type StaticKeys = { privateKey: Uint8Array; publicKey: Uint8Array }
type Binding = { remoteStatic: Uint8Array; handshakeHash: Uint8Array }
type NativeBinding = {
  generateStatic(): StaticKeys
  create(initiator: boolean, local: Uint8Array, remote: Uint8Array | null, prologue: Uint8Array): number
  record(handle: number, operation: number, input: Uint8Array, output: Uint8Array): number
  finish(handle: number): Binding
  rekey(handle: number, send: boolean): void
  close(handle: number): void
}

let binding: NativeBinding | undefined
function native(): NativeBinding {
  if (binding) return binding
  try {
    binding = createRequire(import.meta.url)('../lib/native/tokenworks_noise.node') as NativeBinding
    return binding
  } catch {
    throw new Error('e2e/unsupported')
  }
}

function bytes(value: Uint8Array): void {
  if (!(value instanceof Uint8Array) || value.buffer instanceof SharedArrayBuffer) throw new Error('e2e/argument')
}

export function generateNoiseStatic(): StaticKeys { return native().generateStatic() }

/** Synchronous state owner. A retained network write must retain its output until consumed. */
export class NativeNoiseSession {
  #handle: number

  constructor(initiator: boolean, localPrivate: Uint8Array, remotePublic: Uint8Array | null, prologue: Uint8Array) {
    if (typeof initiator !== 'boolean') throw new Error('e2e/argument')
    bytes(localPrivate)
    if (remotePublic !== null) bytes(remotePublic)
    bytes(prologue)
    this.#handle = native().create(initiator, localPrivate, remotePublic, prologue)
  }

  #record(operation: number, input: Uint8Array, output: Uint8Array): number {
    if (!this.#handle) throw new Error('e2e/closed')
    bytes(input)
    bytes(output)
    try { return native().record(this.#handle, operation, input, output) } catch (failure) {
      // Native retires failed state and zeroes caller output, including authentication failures.
      this.#handle = 0
      throw failure
    }
  }

  handshakeWrite(payload: Uint8Array, output: Uint8Array): number { return this.#record(0, payload, output) }
  handshakeRead(record: Uint8Array, output: Uint8Array): number { return this.#record(1, record, output) }
  encrypt(plaintext: Uint8Array, output: Uint8Array): number { return this.#record(2, plaintext, output) }
  decrypt(record: Uint8Array, output: Uint8Array): number { return this.#record(3, record, output) }

  finishHandshake(): Binding {
    if (!this.#handle) throw new Error('e2e/closed')
    try { return native().finish(this.#handle) } catch (failure) {
      this.#handle = 0
      throw failure
    }
  }

  #rekey(send: boolean): void {
    if (!this.#handle) throw new Error('e2e/closed')
    try { native().rekey(this.#handle, send) } catch (failure) {
      this.#handle = 0
      throw failure
    }
  }

  rekeySend(): void { this.#rekey(true) }
  rekeyReceive(): void { this.#rekey(false) }

  close(): void {
    const previous = this.#handle
    this.#handle = 0
    if (previous) native().close(previous)
  }
}
