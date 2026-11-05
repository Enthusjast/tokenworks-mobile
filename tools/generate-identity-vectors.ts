/** Regenerate public test-only hello/proof examples after an identity cutover. */
import { createHash, createPrivateKey, sign } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import { hostHelloDigest } from '../host/remote-control/src/relay-client.js'

const helloURL = new URL('../contracts/security/relay-device-hello.json', import.meta.url)
const hello = JSON.parse(await readFile(helloURL, 'utf8')) as { device_id: string; protocols: number[]; capabilities: string[]; hello_sha256: string }
hello.hello_sha256 = hostHelloDigest(['device', hello.device_id, hello.protocols.join(','), hello.capabilities.join('\n')])
await writeFile(helloURL, JSON.stringify(hello, null, 2) + '\n')
const proofURL = new URL('../contracts/security/file-read-proof.json', import.meta.url)
// This pre-existing fixture explicitly contains a public test-only private key.
// Never use a deployed identity/key as generator input.
const proof = JSON.parse(await readFile(proofURL, 'utf8'))
if (proof.description !== 'Public test-only P-256 key material; never use for deployment.') throw new Error('Expected the public test-only fixture')
proof.header = { typ: 'tokenworks-mobile-file-read-proof+jwt', alg: 'ES256' }
proof.claims.aud = 'urn:tokenworks-mobile:file-read'
proof.claims.ath = createHash('sha256').update(proof.identity.token).digest('base64url')
const input = [proof.header, proof.claims].map(value => Buffer.from(JSON.stringify(value)).toString('base64url')).join('.')
proof.compact = `${input}.${sign('sha256', Buffer.from(input), { key: createPrivateKey({ key: proof.private_jwk, format: 'jwk' }), dsaEncoding: 'ieee-p1363' }).toString('base64url')}`
await writeFile(proofURL, JSON.stringify(proof, null, 2) + '\n')
console.log('Regenerated hello digest and file-read proof from public test-only material.')
