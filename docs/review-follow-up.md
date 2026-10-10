# External review follow-up — 2026-10-10

Source: [shared Grok review and proposed T-01–T-15 backlog](https://grok.com/share/c2hhcmQtNQ_f7fa516c-4adf-4df6-a205-835668fb65cc).
Recommendations are review input, not replacement architecture requirements.

| Review item | Disposition |
|---|---|
| T-01 license | Owner decision pending; no license selected on the owner's behalf. |
| T-02 source references | Broken references in test comments and ADR-001 corrected. Publishing the full source specifications or a reviewed normative extract remains open. |
| T-03 layering | `verifyModuleLayering` verifies actual Gradle production dependencies and cycles; wired to `check`. |
| T-04 README | Runtime scope clarified, machine-specific verification path and stale test total removed; CI linked. SOTA OS name retained. |
| T-05 credential persistence | Existing local provider now supports real rotate/revoke with revisions and immutable audit. General Account persistence and status lifecycle remain open; no parallel authentication store added. |
| T-06 Person isolation | No-op-based test removed; SQLite authentication, rotation, revocation and reopen tested in PersonAccountIsolationTest. |
| T-07 Trust/Competence | Review storage coverage and source rules before changing AuthorityBasis eligibility. Not completed by this change. |
| T-08 agent module | Keep the explicit deferred/inert status; do not invent registry behavior or remove a binding module without a source decision. |
| T-09 action vocabulary | Evaluate separately against extensible action/scope semantics; do not impose an unreviewed closed vocabulary. |
| T-10 event immutability | SQLite update/delete denial triggers already exist. Further review of direct-SQL/correction coverage remains open. |
| T-11 rights policies | Broader command authorization remains open. Preserve the existing transactional P05 consent/authority checks during any extraction. |
| T-12 test tiers | Placement remains concentrated in `test/`; ADR-007 now distinguishes current state from target. |
| T-13 bootstrap audit | Define actor/provenance semantics before introducing a new system subject or unsigned journal producer. |
| T-14 independent two-node run | [Reproducible rehearsal](getting-started.md): Core Loop → strict HTTPS recovery → signed exit, with governed fixture provisioning and separate CLI listener processes. General production bootstrap and physical multi-host deployment remain outside this fixture. |
| T-15 secret handling | Existing P09/P10 smokes retained; P01 terminal lifecycle and secret exclusion added to CI. NodeMetricsJsonTest already exercises the restricted JSON shape. |

Completed milestones: local P01 lifecycle, module graph verification, and the
independently reproducible two-node rehearsal. Next: extend signed Core Loop producers
beyond action facts, starting with Result and its causal link to the action.
The review's estimates and blanket prohibition on new ADRs were not adopted as
requirements. Schema/protocol decisions still require the project's usual reasoning.
