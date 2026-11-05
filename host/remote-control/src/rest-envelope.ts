/** Only the service REST transports negotiate this format. Issuer/JWKS, object
 * upload/download and WebSocket payloads keep their independent contracts. */
export const restEnvelopeHeader = 'x-tokenworks-mobile-response-envelope'
export const restEnvelopeVersion = 'request-id.v1'
const requestID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

/** Remove only verified transport metadata before the existing strict business
 * schemas run. Older peers may return their original envelope without an ack. */
export function normalizeRestEnvelope(value: Record<string, unknown>, headers: Headers): Record<string, unknown> {
  const version = headers.get(restEnvelopeHeader)
  const invalid = () => new Error('remote response envelope invalid')
  if (version === null) {
    if (Object.hasOwn(value, 'request_id')) throw invalid()
    return value
  }
  const id = value.request_id
  if (version !== restEnvelopeVersion || typeof id !== 'string' || !requestID.test(id) || headers.get('x-request-id') !== id ||
      Object.keys(value).length !== 3 || typeof value.ok !== 'boolean' || !Object.hasOwn(value, value.ok ? 'data' : 'error')) throw invalid()
  const { request_id: _correlation, ...business } = value
  return business
}
