# Core Genesis Mandate — source and model review

Reviewed: 2026-11-28. Companion to **PROPOSED** [ADR-032](adr/ADR-032-core-genesis-mandate.md).

## Source standing and reproducibility

- Constitution: Ukrainian source revision `430621221c7873936211f6fe0533b451ff50ca76`.
  Its [README][constitution] and [Article 102.6][xii] explicitly say it is **not adopted**.
  Reviewed preamble, Sections I–VIII and XII for this bounded decision. This is not
  completion of the repository's pending A01–A21 cross-axiom or all-section review.
- Axioms: local ODT **engineering traceability notes**, mostly marked working v0.1,
  for A01, A03–A08, A11, A14 and A17. The source manifest records exact filenames and
  ODT SHA-256. Reviewed relevant source/principle sections; these notes include
  interpretations and are not certified as the latest published Doctrine. The public
  Doctrine site could not be retrieved during review. No personal source files are
  copied into this repository. A21 is unresolved in the Constitution itself.
- Implementation: SOTA-OS `736ceb824401d39dd790f4a8384d62b018546d95`, including
  the owner's proposal at `6a6c0a59`. Code and accepted local ADRs establish what the
  MVP does; they cannot establish constitutional adoption or a particular subject's rights.
- [Source manifest](core-genesis-mandate-sources.json): hashes of immutable public
  source text and local ODT bytes, with explicit provenance limitations. Local sources
  require access to the owner's archive to reproduce; CI does not claim to fetch them.

## Normative trace and engineering consequences

| Decision / limit | Constitution at pinned revision | Axiom-note connection | Consequence and acceptance IDs |
|---|---|---|---|
| Delegated, bounded founding power; no host or majority self-authorization | [I Arts. 3, 6][i]; [VII Arts. 49–51, 53–54][vii] | A05 §§3–4 distinguishes trust from permission; A06 §§3, 8–10 separates competence/status from unlimited power; A07 §3 describes a temporary holder | Verify source entitlement per action/resource; no fabricated parent. GEN-03/04/16 |
| Voluntary agreement and exit, no forced future participants | [III Arts. 17, 20][iii]; [V Arts. 31, 34–37][v]; [XII Art. 100][xii] | A03 §§2–4 responsible autonomy; A04 §§3–5 rights constrain system goals | Bound founding roster and individual delegations; membership alone grants nothing. GEN-01/02/08/09 |
| Unanimity at founding, proposed 2/3 renewal | [VII Art. 54][vii] allows specified lawful decision procedures; [XII Art. 96.4][xii] denies unlimited legitimacy even to unanimity | A08 §3 self-governance; A17 §§3–4 plural organizational forms | **Numbers come from the owner's proposal, not a quoted constitutional threshold or derivation from an axiom.** GEN-01/05/06 |
| Single-subject founding; no automatic external jurisdiction | [V Art. 31][v]; [VI Arts. 40–48][vi]; [XII Arts. 100, 102][xii] | A08 §3 and A17 §§3–4 support self-organization, not a prescribed minimum roster | Single-founder mechanism is a design proposal compatible in direction, not a claim that the Constitution explicitly specifies its storage/ballot algorithm. GEN-01/04 |
| Revocability, expiry, accountability and surviving obligations | [IV Arts. 27–29][iv]; [VII Arts. 51–52, 55, 57][vii]; [XII Art. 99][xii] | A03 §§2–4; A07 §3 links service, scope, responsibility and expiration; A11 §3 treats institutions as instruments | No perpetual mandate, self-extension or deletion of responsibility. Finite duration is proposed policy; the Constitution also speaks of cessation conditions. GEN-07/08/09/16 |
| Equal intellect-subject standing; technical behavior is not recognition | [I Arts. 1, 4][i]; [II Arts. 7–12][ii] | A01 §§2, 6 and A14 §§1–3 use human-centred primacy/instrument language | **Tension with existing engineering policy**, Q01. Do not infer AGENT subjecthood or weaken equality for code convenience. GEN-17 |
| Conflict disclosure, independent review and limited enforcement | [VIII Arts. 59, 62, 64][viii]; [VII Art. 56][vii]; [XII Art. 98][xii] | A04 §§3–5 limits even effective authority; A07 §3 service/accountability | Core cannot be final judge of its own power; signatures and logs are evidence, not legitimacy by themselves. GEN-10/16 |
| Source authenticity, memory/privacy and no hidden adoption | [III Art. 21][iii]; [VIII Art. 62][viii]; [XII Arts. 101–102][xii] | A03 §4 asks who acted, on what basis and with what consequences | Immutable evidence with bounded access; migration is not ratification. GEN-11/12/18/19 |
| Empty collective and unsettled obligations | [I Art. 5][i]; [V Art. 39][v] | A03 responsible autonomy; A17 variable collective form | No zero-member “will” or zero-vote mandate; preserve history/assets/obligations for separately authorized handling. GEN-06/09 |

The Genesis invariant concerns voluntary constitutive delegation. Article 50's
broader lawful-source formulation and unresolved A21 must not be silently replaced
by a universal claim that every possible protective or arbitral competence has this
same source. This feature supplies no nonconsensual jurisdiction.

## Compatibility with actual models

Paths below refer to the reviewed implementation revision; none is changed by ADR-032.

| Model / existing evidence | Compatible foundation | Gap and required boundary |
|---|---|---|
| [Identity](../domain/src/main/kotlin/sotaos/domain/identity/Identity.kt), [ADR-009](adr/ADR-009-provider-based-authentication.md) | Person distinct from Identity and Account; authentication is explicit | Identity.ownerPerson is PersonId. Neither fresh proof nor key ownership proves constitutional recognition/capacity. Digital/nested subjects need Q01/Q02; never cast them to Person |
| [Membership / Core / Sota](../domain/src/main/kotlin/sotaos/domain/collective/Collective.kt), [ADR-020](adr/ADR-020-persisted-membership-admission.md), [ADR-021](adr/ADR-021-authenticated-core-admission.md) | Persisted lifecycle; Core and founder membership atomically created; authenticated representatives consume explicit scoped mandates | Membership.subject is PersonId. Core creation supplies no genesis root. Representative admission already assumes a root; it cannot bootstrap one. Clarify collective/holder mapping and founder roster separately |
| [AuthorityService](../application/src/main/kotlin/sotaos/application/services/AuthorityService.kt), [AuthorityLineage](../application/src/main/kotlin/sotaos/application/services/AuthorityLineage.kt), [ADR-013](adr/ADR-013-delegation-lineage.md) | Stored single-parent ancestry, active/expiry/scope checks and cascading revocation | Parentless Core/Sota roots rely on host standing. Person/Agent issuers require a parent. Trust/competence/note fields do not verify source entitlement. Add separate original-source and multi-source dependency evidence; prevent circular source proof |
| [ConsentService](../application/src/main/kotlin/sotaos/application/services/ConsentService.kt), [command audit](command-authorization.md) | Explicit purpose/context/scope, affirmative consent and withdrawal | Existing consent concerns Person and does not bind signed founding revision, roster or electorate. Do not reinterpret existing consent as a founding vote. Empty resource scopes have broad matching semantics: require explicit bounded mandate resources |
| Audit / [ADR-012](adr/ADR-012-historical-signature-evidence.md), [ADR-016](adr/ADR-016-historical-record-approval.md), [ADR-025](adr/ADR-025-local-memory-audit.md) | Append-only facts, exact verified records and governed historical approval | Local unsigned audit is not a signed remote mandate. New approval domain, canonical test vectors, nonce/epoch binding and atomic consumption required. Historical receipt ≠ present signing power |
| [Memory](../domain/src/main/kotlin/sotaos/domain/memory/Memory.kt), [ADR-004](adr/ADR-004-sync-conflict-resolution.md), [P09](p09-sync.md) | Event provenance, immutable facts, causal merge and explicit conflicts | No Genesis event projection exists. Sync admission does not execute commands or prove mandate legitimacy. Add a family-specific deterministic projection, pending dependencies and conservative revocation handling |
| [ADR-006](adr/ADR-006-ai-boundary.md) / AGENT | Inert tool boundary, no autonomous canonicalization or authority | Equal constitutional subject protection does not silently supersede this technical boundary. Separate subject-recognition and implementation-policy decision required |
| [P10 mapping](ac-p09-mapping.md), [ADR-014](adr/ADR-014-relation-inventory.md) | Explicit self-exit, typed relations, governed export and retained obligations | Mandate-source withdrawal is not automatic membership exit or universal settlement. Multiparty consent, dispute/continuity policies remain open |

Review conclusion: **partially compatible foundations; not implementable as a mere
Authority insert or automatic schema backfill**. The main unresolved decisions are
listed separately in [Q01–Q09](core-genesis-mandate-questions.md). The production
schema and existing trusted-host contract remain unchanged pending explicit rollout.

[constitution]: https://github.com/Khmelyuk/SOTA-Constitution/blob/430621221c7873936211f6fe0533b451ff50ca76/README.md
[i]: https://github.com/Khmelyuk/SOTA-Constitution/blob/430621221c7873936211f6fe0533b451ff50ca76/constitution/uk/01-foundations.md
[ii]: https://github.com/Khmelyuk/SOTA-Constitution/blob/430621221c7873936211f6fe0533b451ff50ca76/constitution/uk/02-subjecthood-and-recognition.md
[iii]: https://github.com/Khmelyuk/SOTA-Constitution/blob/430621221c7873936211f6fe0533b451ff50ca76/constitution/uk/03-fundamental-rights-and-freedoms.md
[iv]: https://github.com/Khmelyuk/SOTA-Constitution/blob/430621221c7873936211f6fe0533b451ff50ca76/constitution/uk/04-responsibility-and-duties.md
[v]: https://github.com/Khmelyuk/SOTA-Constitution/blob/430621221c7873936211f6fe0533b451ff50ca76/constitution/uk/05-sota-and-self-organization.md
[vi]: https://github.com/Khmelyuk/SOTA-Constitution/blob/430621221c7873936211f6fe0533b451ff50ca76/constitution/uk/06-jurisdiction.md
[vii]: https://github.com/Khmelyuk/SOTA-Constitution/blob/430621221c7873936211f6fe0533b451ff50ca76/constitution/uk/07-legitimacy-and-delegation.md
[viii]: https://github.com/Khmelyuk/SOTA-Constitution/blob/430621221c7873936211f6fe0533b451ff50ca76/constitution/uk/08-arbitration-and-justice.md
[xii]: https://github.com/Khmelyuk/SOTA-Constitution/blob/430621221c7873936211f6fe0533b451ff50ca76/constitution/uk/12-amendments-and-constitutional-guarantees.md
