# ADR-018 — Human-invoked local Research CLI

Status: Accepted for the local MVP slice
Date: 2026-10-11

The `api` composition root may depend on `agent` to expose its candidate-only
research workspace. This explicitly extends ADR-007's allowed `api` edges; all
other module edges remain unchanged. ADR-006 stays in force: no AGENT actor,
Action execution, canonicalization or publishing runtime is introduced.

Every research operation uses fresh ADR-009 provider proof and current scoped
revocable authority, scoped to both `research-workspace` and
`research-unit:<SotaId>`. A shared Person binding across units does not transfer the grant.
CLI enables only the installed local-passphrase provider.
Workspace storage is derived from the database path and a hash of the Sota unit,
not from arbitrary remote inputs. Import is explicit bounded local public data;
Moltbook is opt-in fixed public GET and honors geo-block. The complete response
(including the body) has a 15-second deadline; timeout cancels the request.

First-use `research setup` requires exact terminal confirmation and verified
Person credentials, then creates a NEW voluntary one-founder Core through the
existing CollectiveService. Its founding local composition grants that Person
only research read/ingest/analyze/draft/approve on the research workspace for
24 hours, through AuthorityService. There is no command to represent an existing
collective, widen a grant, grant to an Agent or infer authority from login alone.
Core, founding membership, grant and human-attributed setup Event commit together
in SQLite. This is the same originating-collective bootstrap model as CoreLoopDemo;
it is not a governance protocol for existing/multiparty Cores.

`research revoke` is voluntary surrender of the authenticated recipient's OWN
bootstrapped research grant in the same Sota unit. The immutable setup Event must
bind that Person, unit and originating Core. AuthorityCascade narrows the grant
and descendants and retains revocation audit under the actual human actor.
This is separate from P04 issuer-driven revocation; its issuer-only contract is
unchanged. Surrender and a human-attributed Event commit atomically.

Governance Events are local unsigned R0/R1 records with content checksums, not
strict signed P09 producers. Research knowledge remains in the separate local
research journal. Unknown options (including password arguments) are rejected.
Report output uses CREATE_NEW and 0600; existing files cannot be overwritten.
Approval displays exact immutable draft text, rejects unsafe terminal controls,
requires a typed hash-bound phrase and revalidates authority before commit.
Network waits and human confirmation hold no SQLite transaction. A fresh authority
check and journal commit share a short transaction, so another connection can revoke
the grant during review and prevent approval. Approval stays draft-only. CLI/UI automation cannot supply confirmation flags.
