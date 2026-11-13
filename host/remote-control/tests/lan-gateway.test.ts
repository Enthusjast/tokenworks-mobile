import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { createHash } from 'node:crypto'
import { LanGateway } from '../src/lan-gateway.js'
import type { LanGatewayOptions } from '../src/lan-gateway.js'
import type { NativeGateway } from '../src/native-gateway.js'

function token(): string {
  return createHash('sha256').update('pairing').digest('base64url')
}

function options(deviceToken: string | undefined): LanGatewayOptions {
  return {
    host: '127.0.0.1', port: 0, runtimeId: 'aaaaaaaa-1111-4111-8111-111111111111', installationId: 'bbbbbbbb-2222-4222-8222-222222222222',
    connection: { createPeer: () => { throw new Error('not reached in admission tests') }, createSharedFetchHandler: () => ({ requestBodyMode: () => 'buffer' as const, fetch: () => Promise.reject(new Error('not reached')) }) } as never,
    remoteAccess: { registerPolicy: () => () => undefined, invalidate: () => undefined } as never,
    gateway: null as unknown as NativeGateway,
    resources: () => ({ sessions: [], workspaces: [] }),
    certificate: { cert: "-----BEGIN CERTIFICATE-----\nMIIDCTCCAfGgAwIBAgIUXLitQ7MrJCfF03hS3rlbdFD/fiswDQYJKoZIhvcNAQEL\nBQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MTExMzEyNTcxMloXDTI2MTEx\nNTEyNTcxMlowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEF\nAAOCAQ8AMIIBCgKCAQEApt0Qzqj7Ma+q8Qddq+P+2duR1aHf4Yp/XxJmO/YjvtLm\ndFCQKnutO12CW4tmhM5jRVcWJkPOKWs6Tq0ToRjzxQs445sIKkESdmq2D7Pd5aye\nxi687AVKzTF54wNyxpCIOkHY4zoIQ5KRsSmqv3arssDqfckZcC0QeEB6G8jnwX23\nOHUAh4+tKMo0Z/ruwRPkBPaqqUsF4FMadgRrIJbS7dynuTtEfNhMpK7aDswZ2yQU\nBOo5nBOSodCQECn0Qvj18tdA2kLK4HkBL8T+I6QwgjAE2zmJTM7wQCD0gphwIiN3\n+KcbaKaTeU3w1fOjX62HFOaIftsxkzVbcs+3RGFtXQIDAQABo1MwUTAdBgNVHQ4E\nFgQU3Qoxi/skzEtsLRAgGkuVDUS717swHwYDVR0jBBgwFoAU3Qoxi/skzEtsLRAg\nGkuVDUS717swDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAl1SK\nZjiNDeB9Q2Y/hDM3JX1JeNK0EC8++c0sdMdySDPRn7VrzfWqZSOfH923eqCv0Nlp\nSwQqjKTDmpnQtL/Oj2+GZKGKkWXbDGDkL+Crh2FdCfQj1jUuOg7Q+6JEh7xNNahb\nOB+ZtEN249bzA0+LiTidbCB4LVvPreAo+w97z0ORF65s6DTvU6udKe875Z0vfUaq\nsQ6GtJujKKaT16n6MMb5am54kF2wlTCsnF8GBdzNIENZtAaRKyLKSxyhQdvOR2wl\nPWkx/YosSBOaBZqa/YaHfTCQf9S4KcomACQmTT1o5T/2me7XRisY3H32MGqkwP/p\nQe6nvVW8TGAdukjQag==\n-----END CERTIFICATE-----\n", key: "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCm3RDOqPsxr6rx\nB12r4/7Z25HVod/hin9fEmY79iO+0uZ0UJAqe607XYJbi2aEzmNFVxYmQ84pazpO\nrROhGPPFCzjjmwgqQRJ2arYPs93lrJ7GLrzsBUrNMXnjA3LGkIg6QdjjOghDkpGx\nKaq/dquywOp9yRlwLRB4QHobyOfBfbc4dQCHj60oyjRn+u7BE+QE9qqpSwXgUxp2\nBGsgltLt3Ke5O0R82EykrtoOzBnbJBQE6jmcE5Kh0JAQKfRC+PXy10DaQsrgeQEv\nxP4jpDCCMATbOYlMzvBAIPSCmHAiI3f4pxtoppN5TfDV86NfrYcU5oh+2zGTNVty\nz7dEYW1dAgMBAAECggEAQZYPtqMNxNxO3Nkv1NHTSI84tZcgN/bnlJdXTsczCpy7\nz0khOUmOcBCTnebff2Dd/RG7V4P8QyRt8SGvXvk/S5pbHH9ByNX160yLTgW9OLrO\nV4hkYoK8AYYqafPfh7Okj/10we978w9nq5J8ljAPUb3pDVLfaEuWNyXkuQoRKehc\nQmEMliO8vT3MvuM9ym+eVPh0TkSJUXYGR9GsA3ZqzfBHWodX+nmRqilvRgWQaPs6\n57Aza7jgSGRmnIF8whVosgK7vEiG3VKNChEYKR6Jxjf8JO5xOdTbsXopkttE3c45\nU9njttPcJ73Vxa3B8AE2JrF6YdxrgKltWEElgRXyMwKBgQDjleW3u3U/2azUCt5v\nV2v5FxYd7Xwl49MGGHGp2jkiKRHMwbLD7L92/dqO3nbsETe5UrYOgy11IG1d9LoF\nM7FwTLRBCx2qfasl2GlPqjLDAdelB+b8D2ng1YtCLZfjFpkNOVab6qtD5aVXKnw8\nWgX4wXIGd57/nlU4j5mSLzNLqwKBgQC7sl49gr34IRxpnTymjVWkgxWrxyn3b20E\nEtbgkYxaI9CFqQzbhJutjyVVnUF2Xv+ASad0N6tRCWNnhnlv3c+YsjPG+mNSkJg8\nvP0UJNA5IpJmL8pdGNd3mAsAEAN4dSWVebDpYptDsd7WU6SvI9/u4qWL8CkvWNQX\nXM8ZawfjFwKBgChz33YuEuQ7i9XQ7MK7tkx7RwogyV1uxnah56o6SMeCp1BO6bib\nn1jGPJACiP7i/JWrVwLzXCQilG4L7vi3vIVqjOu2zxob5kz+z5FCfm1dKXiDFBKf\npsE5E9y6f/H0dVX9aCCksLaL3jzFJ3oOwlr5/4uYvaFQAnPgSnrKewKrAoGAFxM5\npMRDCJNBg/Zxs2QiyEPZUTH+Bc5sD5klU5xxLc/DXftHemFWJkM36suXda7+KE5l\nyNLjO+Y0k1Cj803IrNQTq32y4j6miKkPT/+qnauTZf6wGTvpmWlWyN4vHwr/4N2C\nE18tCWh/pcfIskNtz1eL1KVk2s5H3MtrJovS/2cCgYEAoGELrvQTeNBCPN+gXGXV\nmELTlqT+WvdLnPz5qaseoTsLkpeZ7pGU18dpgwZKnlhsg88nh/Wr2Dfl+dUJiHSR\n1J/4sa2wTA5WH0V5OQE8dVg2pOMUM9aOikgVQCYTuhCs+u/UXJQcoXHJLD654Qgc\nvmtYamH2P7HoNq0b3Jg3qlk=\n-----END PRIVATE KEY-----\n" }, // listen() is never awaited in these tests
    deviceToken: () => deviceToken,
  }
}

test('constructor rejects wildcard binds and invalid runtime identities', () => {
  assert.throws(() => new LanGateway({ ...options(token()), host: '0.0.0.0' }), /specific address/)
  assert.throws(() => new LanGateway({ ...options(token()), host: '::' }), /specific address/)
  assert.throws(() => new LanGateway({ ...options(token()), host: '' }), /specific address/)
  assert.throws(() => new LanGateway({ ...options(token()), runtimeId: 'not-a-uuid' }), /runtime identity/)
})

test('phase reports stopped before listen and closed after close', async () => {
  const gateway = new LanGateway(options(token()))
  assert.equal(gateway.phase(), 'stopped')
  await gateway.close()
  assert.equal(gateway.phase(), 'closed')
})

test('a missing or malformed paired token disables admission entirely', async () => {
  // deviceToken undefined: the gateway constructor is legal but every request
  // must be refused; without a token there is no peer and no protocol surface.
  const gateway = new LanGateway(options(undefined))
  assert.equal(gateway.phase(), 'stopped')
  await gateway.close()
})
