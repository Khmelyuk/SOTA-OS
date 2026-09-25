# ADR-003a - Event signatures

Status: ACCEPTED
Date: 2026-09-25

## Decision

- Use JDK-backed Ed25519 for actor event signatures.
- Sign UTF-8 `Event.contentHash`, not transport or sync metadata.
- Encode signatures as unpadded Base64 with the `ed25519:` marker.
- Keep signing and verification in `security`; Domain and Application do not
  handle private keys or JCA classes.
- R0/R1 may remain unsigned; configured R2+ admission requires a valid actor
  signature and trusted public key.

See `security.EventSigner`, `EventSignatureAdmission`, and `ActorKeyDirectory`.