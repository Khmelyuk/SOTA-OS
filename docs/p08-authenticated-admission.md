# Authenticated admission to a Core

`api.collective.P08AdmissionRuntime` is an opt-in local host for one concrete
collective operation: a delegated Person issues a scoped invitation, which the
invited Person explicitly accepts. It uses local-passphrase authentication, existing P04 authority lineage,
P08 persisted membership and append-only human audit.

## Provisioning contract

The trusted host must already have:

- An ACTIVE Core and both Person records.
- Local credentials bound to those People in the runtime's Sota unit.
- An independently established invitation authority for the representative,
  accountable to the Core and originating from that Core through a recorded valid
  chain. The runtime does not issue or approve the initial mandate.
- A configured governance context and RightsConstraint policy.

The authority's action set includes `membership.invite`. Its explicit resources
include `core:<core-id>`, `membership-unit:<unit-id>` and `person:<invitee-id>`.
Each ancestor must cover the delegated scope, context, validity and accountability.
Membership, founder status, role, a Core ID or a valid login alone are insufficient.

## Host API

Construct `P08AdmissionRuntime(store, unit, governanceContext, clock, ids, rights)`.
Collect proof through a protected local interface outside the store gate; display
the exact recipient, Core, optional role and purpose before submitting a command.

```kotlin
val challenge = runtime.begin(representativeHandle)
val invitation = runtime.invite(challenge, representativeProof, CoreInvitationRequest(
    coreId, invitedPersonId, roleId, invitationAuthorityId, "Invite a collaborator"
))
// After the invited Person reviews the stored terms, obtain fresh proof for that Person.
val acceptance = runtime.begin(inviteeHandle)
val membership = runtime.accept(acceptance, inviteeProof, invitation, "Accept these terms")
```

Proofs are mutable `CharArray`s and are cleared by the runtime, including on error.
No caller-supplied actor/session is accepted. Challenges are single-use, expiring
and local to this runtime instance; obtain a new challenge after any failure or
restart. An issued `RecordedInvitation` can survive reopening SQLite; its audit ID
and complete membership terms must be retained and supplied unchanged.

The invited Person must match the stored proposal. The originating invitation's
mandate is checked again at acceptance, so expiry, revocation, suspended ancestry
or an inactive Core prevents entry. Both successful commands atomically persist
membership state and human-attributed audit. Granting authority to invite never
activates membership automatically and does not grant authority to the new member.
An explicit mandate may name the representative as its recipient; acceptance still
requires a separate fresh proof and never follows automatically from invitation.

## Audit and operational scope

The invitation audit records the actual human representative, authority ID,
configured context, purpose, provider ID, Sota unit and complete membership terms.
The acceptance audit records the invited Person and links to that invitation.
Credential proof and handle are excluded. Audit Events cannot be updated/deleted
through SQLite's existing Event protections.

These are local unsigned R0/R1 Events. Canonical checksums detect inconsistent
stored content; they are not signatures or independent proof of a collective
decision. The database and its direct repository APIs remain trusted. Do not
expose raw CollectiveService or arbitrary repository writes to untrusted callers.
Strict P09 export rejects unsigned records: this adapter is not yet a signed P08
producer, and adding it to a strict-sync database can block export of that history.

All users of the same SQLite connection must use `store.withLocalAccess`. Local
credential verification occurs before the business transaction; a revision/binding
recheck, authority reads, membership write and audit append share the transaction.
Failed authentication counters survive command rejection. No network, terminal
wait, external publication or collective root issuance happens inside the runtime.

AuthenticatedAdmissionTest, AdmissionAuthorityTest and AdmissionAtomicityTest cover
real provider proofs, challenge replay/expiry, credential revocation, exact terms,
unit/context isolation, scoped lineage, pending-invitation revocation, rights denial,
rollback, reopen and human audit. [ADR-021](adr/ADR-021-authenticated-core-admission.md)
maps the reviewed source constraints and distinguishes the remaining governance work.
