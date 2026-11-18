# ADR-021 — Authenticated, delegated Core admission

Date: 2026-11-18
Status: Accepted for an opt-in local admission host

## Sources reviewed

The original local PDFs were reviewed for this increment. The following are
paraphrased constraints, not a claim that their complete protocols are implemented.

| Source | Relevant constraint | Implementation evidence |
|---|---|---|
| Protocol Architecture v0.1 §7, PDF p.7 | Traceable source, target, scope, context, validity and accountability for delegation; role does not imply authority | CoreInvitationAuthorization; AdmissionAuthorityTest |
| Protocol Architecture v0.1 §11, PDF p.11 | Explicit agreed terms and voluntary interaction; no automatic membership | Persisted proposal followed by independently authenticated acceptance; AuthenticatedAdmissionTest |
| Security & Trust Architecture v0.1 §§13–14, PDF pp.9–10 | Authentication is distinct from authority; authorization checks identity, scope, context, policy, rights and state | Fresh P01 proof plus current mandate and both representative/collective rights checks |
| Security & Trust Architecture v0.1 §22, PDF p.14; §29, PDF p.18 | Explicit bounded delegation; representation does not confer full authority or ownership | Full stored lineage, exact Core/unit/recipient resources, no generic collective impersonation or root-grant endpoint |

Source fingerprints:

- `03 Protocol Architecture v0.1.pdf`: SHA-256 `7bfb4bcf222e74b2f6b956b9ea11667692e10349aa7e8f6236888e25bad61e41`.
- `04 Security Trust Architecture v0.1.pdf`: SHA-256 `3edb5eafa44ae4283f53c18f2ad7e8419ca224a215357768f0f3f51e00875bd1`.

The sources do not specify a complete executable rule for authenticating a
multiparty collective decision or approving its initial root grant. That remains
open. This increment consumes an already recorded mandate instead of treating
login, founder status or membership as permission to create one.

## Decision

Add `api.collective.P08AdmissionRuntime` with explicit `begin`, `invite` and
`accept` operations. Each mutating call consumes fresh local-passphrase provider
proof and derives its Person from the configured unit's durable binding. The API
does not accept a claimed actor, reusable AuthenticatedSession, or generic callback
to run as a collective. Secret arrays are cleared on every outcome.

An invitation requires a stored authority held by that Person, accountable to
the target ACTIVE Core, in the host-configured governance context. Its action
scope must include `membership.invite`, and its explicit resources must include
the target `core:<id>`, `membership-unit:<id>` and invited `person:<id>`.
An unrestricted/empty resource list does not substitute for this explicit mandate.
Every ancestor must be current and valid under ADR-013, and the recorded root
issuer must be the target Core. Unknown legacy Person-root ancestry is insufficient.

The representative's rights check receives the actual Person, authority, context
and requested scope. Only after that check does the adapter call ADR-020's
collective-issued invitation path, retaining its separate rights check. This
represents the Core for one bounded operation; it grants no root issuance,
membership removal or other collective power. Roles confer no additional authority.

An invited Person accepts a `RecordedInvitation`, which identifies the exact
stored proposal and invitation audit Event. Acceptance authenticates that Person,
checks the audit's unit/context/terms/checksum, revalidates the issuing authority
and full lineage, then applies the exact-current-record P08 transition and rights
check. A pending invitation does not survive mandate expiry/revocation as an
irrevocable admission right. That conservative rule is specific to this runtime;
it does not rewrite the lower-level trusted-host service contract.

## Atomicity and audit

The shared store gate protects all local access. Authentication finishes outside
the business transaction so failed-proof counters persist. Inside the transaction
the local credential revision and Person binding are confirmed again, followed by
authorization, membership mutation and human-attributed Event append. Any failure
rolls back membership/audit together; authentication challenges remain consumed.
No transaction is held while a user enters proof.

`MEMBERSHIP_INVITED` names the authenticated representative and authority;
`MEMBERSHIP_ACCEPTED` names the invited Person and references the invitation Event.
Payloads preserve full membership terms, purpose, unit and provider ID, never proof
or login handle. They are append-only local R0/R1 audit records with canonical
checksums, not signed P09 facts or remotely verifiable collective mandates.

## Limits and compatibility

No schema migration or new CLI command is introduced. Direct CollectiveService
remains a trusted-host API under ADR-020. This runtime accepts only its recorded
invitation audit format, not arbitrary old proposals. Initial mandates, collective
decision legitimacy, Sota-wide representation, role-specific invitation policy,
signed P08 delivery and general remote dispatch remain separate work.

Membership never creates Authority automatically. P10 continues to handle governed
export and exit settlement. Existing Core Loop and Research bootstrap behavior is
unchanged. See [the host contract](../p08-authenticated-admission.md).
