import { createHash } from 'node:crypto'
import { calculateJwkThumbprint, importJWK } from 'jose'
import { z } from 'zod'
import { canonicalObject, parseSigningObject } from './canonical.js'
import { TrustedApprovalAuthority } from './approval-lease.js'

export const APPROVAL_AUTHORITY_DESCRIPTION_FORMAT = 'dsh-approval-authority/v1'
const sha256 = z.string().regex(/^[0-9a-f]{64}$/)
export const ApprovalAuthorityDescription = z.strictObject({ format: z.literal(APPROVAL_AUTHORITY_DESCRIPTION_FORMAT), authority: TrustedApprovalAuthority,
  policy: z.strictObject({ version: z.number().int().safe().min(1), assurance_profile: z.string().regex(/^[a-z0-9][a-z0-9./_-]{0,127}$/),
    max_auth_age_seconds: z.number().int().min(0).max(900) }), sha256 })
export type ApprovalAuthorityDescription = z.infer<typeof ApprovalAuthorityDescription>
const invalid = () => new Error('approval: invalid pinned authority description')

/** The digest MUST come from independently trusted deployment configuration,
 * never from the document or peer being inspected. This function fetches no URL
 * and never updates a running approval owner, cache or policy. */
export async function parsePinnedApprovalAuthorityDescription(raw: Uint8Array | string, trustedSHA256: string): Promise<Readonly<ApprovalAuthorityDescription>> {
  try {
    if (!sha256.safeParse(trustedSHA256).success || Buffer.byteLength(raw) > 16 * 1024) throw invalid()
    const document = ApprovalAuthorityDescription.parse(parseSigningObject(raw))
    const { sha256: claimed, ...body } = document
    const digest = createHash('sha256').update(canonicalObject(body)).digest('hex')
    if (digest !== trustedSHA256 || claimed !== digest) throw invalid()
    const anchor = document.authority
    if (Date.parse(anchor.valid_until) <= Date.parse(anchor.valid_from) ||
        [anchor.public_jwk.x, anchor.public_jwk.y, anchor.key_jkt].some(value => Buffer.from(value, 'base64url').toString('base64url') !== value) ||
        await calculateJwkThumbprint(anchor.public_jwk, 'sha256') !== anchor.key_jkt) throw invalid()
    await importJWK(anchor.public_jwk, 'ES256')
    return Object.freeze({ ...document, policy: Object.freeze({ ...document.policy }),
      authority: Object.freeze({ ...anchor, public_jwk: Object.freeze({ ...anchor.public_jwk }) }) })
  } catch { throw invalid() }
}
