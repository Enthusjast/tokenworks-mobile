export interface HostHandshakeSigner {
  /** Sign with the registered installation P-256 key; return IEEE-P1363 r||s. */
  sign(signingInput: Uint8Array): Promise<Uint8Array> | Uint8Array
}

export interface InstallationPublicJwk {
  readonly kty: 'EC'
  readonly crv: 'P-256'
  readonly x: string
  readonly y: string
}

export interface HostConnectionCredential {
  readonly installationId: string
  readonly credential: string
  readonly signer: HostHandshakeSigner
  readonly publicJwk: InstallationPublicJwk
}

export interface HostCredentialProvider {
  readonly origin?: string
  readonly signal?: AbortSignal
  acquire(signal: AbortSignal): Promise<HostConnectionCredential>
}
