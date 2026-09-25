# ADR-009 — Provider-based authentication per Sota unit

**Status:** Accepted for the local MVP slice  
**Date:** 2026-09-24

## Context

`Person`, `Identity`, and access credentials are distinct concepts. A Sota
unit may trust more than one authentication source, and another unit may
enable a different set. Local passphrase, Google/OIDC, Diia, qualified
electronic signatures, and future providers must not become special cases in
consent or action logic.

## Decision

- Application code calls the provider-neutral `AuthenticationService` and
  `AuthenticationProvider` begin/complete contract.
- Each Sota unit receives an explicit allowlist of provider IDs. The current
  CLI composition enables only `local-passphrase`; provider integrations are
  not claimed to exist yet.
- A provider returns its verified provider subject. A separate binding maps
  `(Sota unit, provider ID, provider subject)` to the stable SOTA `PersonId`.
  The provider subject and proof never replace `PersonId` in domain decisions.
- Linking an external subject to a Person is a trusted enrollment action and
  must happen only after the provider or a trusted operator verifies that
  link. Provider-specific tokens, signatures, and claims stay in the adapter.
- The initial local adapter uses a unique salted PBKDF2-HMAC-SHA256 verifier,
  hides passphrases at the terminal, clears transient character arrays,
  and delays repeated failed attempts. The work factor is 600,000 iterations,
  following the [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
  recommendation for PBKDF2-HMAC-SHA256. It does not establish civil identity.
- Consent grant, revocation, and listing require a provider-authenticated
  session. Existing `IdentityProtocol.authenticate` is deprecated and fails
  closed because its legacy values cannot verify proof.

## Consequences

Adding Google/OIDC, Diia, or qualified-signature support requires an adapter
implementing `AuthenticationProvider`, a verified subject-binding workflow,
and enabling its provider ID in that Sota unit's configuration. The core
consent rules remain unchanged. Persistent management of the provider
allowlist, account recovery, external identity linking UX, and provider
integrations remain future work.

The local `auth create` and `auth enroll` commands are bootstrap operations.
For an existing Person, `auth enroll` relies on a trusted local operator to
verify the person before binding a passphrase. A person ID or local handle is
not proof of identity.
