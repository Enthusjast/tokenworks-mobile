import assert from 'node:assert/strict'
import { createInterface } from 'node:readline'
import { NativeNoiseSession, generateNoiseStatic } from '../../host/remote-control/src/native-noise.ts'

const hex = bytes => Buffer.from(bytes).toString('hex')
const unhex = value => Buffer.from(value, 'hex')
const empty = Buffer.alloc(0)
const output = Buffer.alloc(65535)

// Decode the public IK1 using the actual production binary (no deterministic RNG API).
const vector = new NativeNoiseSession(false,
  unhex('4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893'), null,
  unhex('4a6f686e2047616c74'))
try {
  const n = vector.handshakeRead(unhex('ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c7944718da798efbcd91528520204f904b9bd6c7413dccdc214d951e15253e39987f18146e8cd0873654207148333479d4d16c289f0294b29960a72f48e0b7bba2e89083169825e59642148d492020664ccf7'), output)
  assert.equal(hex(output.subarray(0, n)), '4c756477696720766f6e204d69736573')
  vector.handshakeWrite(empty, output)
  assert.equal(vector.finishHandshake().handshakeHash.length, 32)
} finally { vector.close() }

const lines = createInterface({ input: process.stdin, crlfDelay: Infinity })[Symbol.asyncIterator]()
async function receive(expected) {
  const result = await lines.next()
  if (result.done) throw new Error('test/unexpected-eof')
  const [command, value] = result.value.split(' ')
  assert.equal(command, expected)
  return value ?? ''
}
function send(command, value = '') { process.stdout.write(`${command} ${value}\n`) }

const initiator = process.argv[2] === 'initiator'
const keys = generateNoiseStatic()
send('KEY', hex(keys.publicKey))
const peer = unhex(await receive('PEER'))
const session = new NativeNoiseSession(initiator, keys.privateKey, initiator ? peer : null,
  Buffer.from('real-production-interop-v1'))
keys.privateKey.fill(0)
try {
  if (initiator) {
    let n = session.handshakeWrite(empty, output)
    send('IK', hex(output.subarray(0, n)))
    session.handshakeRead(unhex(await receive('IK')), output)
  } else {
    session.handshakeRead(unhex(await receive('IK')), output)
    const n = session.handshakeWrite(empty, output)
    send('IK', hex(output.subarray(0, n)))
  }
  const binding = session.finishHandshake()
  assert.deepEqual(binding.remoteStatic, new Uint8Array(peer))
  send('BIND', `${hex(binding.remoteStatic)} ${hex(binding.handshakeHash)}`)
  for (;;) {
    const result = await lines.next()
    if (result.done) break
    const [command, value = ''] = result.value.split(' ')
    try {
      if (command === 'CLOSE') { session.close(); session.close(); send('OK'); continue }
      if (command === 'REKEY_SEND') { session.rekeySend(); send('OK'); continue }
      if (command === 'REKEY_RECEIVE') { session.rekeyReceive(); send('OK'); continue }
      if (command === 'ENCRYPT' || command === 'DECRYPT') {
        output.fill(0xa5)
        const n = command === 'ENCRYPT' ? session.encrypt(unhex(value), output) : session.decrypt(unhex(value), output)
        send('DATA', hex(output.subarray(0, n)))
      } else throw new Error('test/command')
    } catch (failure) {
      if (command === 'DECRYPT' && !String(failure.message).startsWith('e2e/closed')) {
        assert.ok(output.every(byte => byte === 0), 'Failed authentication must wipe caller output')
      }
      assert.match(failure.message, /^e2e\//)
      send('ERROR', failure.message)
    }
  }
} finally { session.close(); lines.return?.() }
