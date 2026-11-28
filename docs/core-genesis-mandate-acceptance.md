# Core Genesis Mandate — acceptance matrix

Status: **PLANNED; no Genesis runtime tests implemented**. Date: 2026-11-28.
Contract: proposed [ADR-032](adr/ADR-032-core-genesis-mandate.md).
Rows depend on approved answers to [Q01–Q09](core-genesis-mandate-questions.md);
they cannot convert an unresolved policy into a passing implementation claim.

Use synthetic subjects, keys and resource entitlements. Exercise domain, SQLite,
authenticated command and P09 boundaries where applicable. For every rejection,
compare authority, membership, consent, event, journal and receipt state before/after;
no partial grant or hidden approval may survive. Record a rejection audit only under
an explicitly defined separate audit contract, without labeling it acceptance.

| ID | Scenario and adversarial input | Required result / evidence |
|---|---|---|
| GEN-01 | One founder; three founders; missing vote; abstention; only connected voters sign | One of one or three of three exact valid approvals can activate. Two of three cannot. Missing/abstaining/offline founder is not silently removed |
| GEN-02 | Change terms, scope or roster after approval; add future entrant; two accounts for one subject | Old approvals cannot authorize changed proposal. Future entrant grants only through their own procedure. One subject has one vote |
| GEN-03 | Delegate another subject's resource; reuse membership, competence score or host role as entitlement; empty resource list | Reject missing source or broadened scope before writes. An unbounded existing Scope must not masquerade as a bounded mandate |
| GEN-04 | Single founder without pre-existing delegated grant; nested Sota founder; cyclic source proof | Verify original capacity or valid representative chain explicitly. No fabricated parent, no circular mandate-proves-itself, no jurisdiction over outsiders |
| GEN-05 | Renew with N=1,2,3,4 and exactly one vote below / at ceil(2N/3); abstentions | Thresholds 1,2,2,3 only if approved by agreement. All electorate members count in N; stored epoch and exact terms bind votes |
| GEN-06 | Membership loss, recusal, withdrawal mid-ballot; N=0; partition leaves minority online | No denominator rewrite or fictive zero-member mandate. Insufficient renewal fails; continuous-support suspension tested separately according to Q04 |
| GEN-07 | Before validFrom, exactly validFrom, just before and exactly validUntil; source expires first; clock rollback/skew | Half-open validity; earliest source cessation limits dependent scope. No self-granted grace period. Uncertain time follows approved freshness policy |
| GEN-08 | Founder withdraws before activation / after activation; holder unavailable; descendant grant exists | No activation with withdrawn source. Known withdrawal prevents dependent future actions and descendants without holder permission; history retained |
| GEN-09 | One source withdrawn from a multi-source mandate; all members leave; old obligations remain | Approved separability rule governs unaffected powers. Affected/ambiguous scope denied; no automatic dissolution/exit/obligation erasure or forced indefinite membership |
| GEN-10 | Holder adjudicates its own challenge; excludes dissenters as conflicted; sole founder declares interest | Independent challenge/recusal path; no self-final ruling or quorum manipulation. Ordinary founding interest alone does not invalidate single-founder route |
| GEN-11 | Replay across Core/Sota, purpose, revision, predecessor, epoch, nonce or validity window; duplicate delivery | Reject changed binding; exact duplicate is idempotent, not another vote. Conflicting statements remain evidence |
| GEN-12 | Forged signature; rotated/revoked key; valid historical receipt presented as current approval; crash during approval consumption | Verify exact canonical payload and applicable key lifecycle. Historical evidence cannot authorize new signing; atomic consumption/evidence/mandate commit |
| GEN-13 | Offline proposal, withdrawal and activation; expired evidence; no freshness witness | Preserve local proposal/withdrawal; activation/execution requires approved evidence freshness. Tests explicitly distinguish safe local recording from authority to act |
| GEN-14 | Partition; concurrent renewal and source revocation; concurrent incompatible founding revisions | Neither node count nor timestamps resolve legitimacy. Preserve facts; deny disputed dependent powers, then reconcile under valid procedure |
| GEN-15 | Reorder, duplicate and delay signed causal facts; missing parent arrives later; reconnect after expiry | Same final projection for delivery permutations; missing ancestry pending, no transient unauthorized activation. Revocation remains effective against older replay |
| GEN-16 | Core extends expiry, changes quorum, appoints itself source, widens rights, suppresses withdrawal or claims emergency exception | Deny all unsupported expansion at issuance and every downstream consumer; preserve review and expiry controls |
| GEN-17 | Human/digital recognized subjects, unrecognized system, technical AGENT, key operator impersonating subject | Apply approved subject/representation policy equally; technical AGENT is not automatically a principal. Existing MVP negative Agent tests remain until explicitly superseded |
| GEN-18 | Existing Core with old founder/current members/legacy roots but missing current constituent procedure | Preserve data without creating formal mandate or approval. Q03 gate remains closed; no automatic presumption of standing |
| GEN-19 | Populated v1 → actual v2, empty fresh v2, repeated opening; all four historical profiles | Exact old rows/signatures/receipts/archives/obligations retained; zero invented genesis approvals/rights. Compare fresh and upgraded target catalog, declared/runtime guards and marker |
| GEN-20 | Failure after each DDL/data/guard boundary, before/after marker, failed COMMIT; abrupt halt under DELETE/WAL | Recover pre-transaction state or committed target; retry once without duplicate evidence. Use actual migration, not only synthetic dispatcher tests |
| GEN-21 | Restore backup predating withdrawal; attempt old binary / forced marker downgrade; reconnect with stale cursor/key state | Fence execution and identity until current evidence and trust are revalidated. No silent authority resurrection or in-place downgrade; preserve post-backup facts |
| GEN-22 | P10 exit concurrent with source withdrawal, renewal or settlement; refused export; retained obligations | Atomic scoped transitions; no grant resurrection, no erased obligations, no forced whole-collective exit. Additional multiparty policies require their own acceptance |
| GEN-23 | P04/P05/P08/P09/P10 consumer holds cached authority while root source changes | Recheck current basis and dependencies in mutation transaction. A previously valid decision/invitation cannot bypass withdrawn or expired root |
| GEN-24 | Sensitive recognition evidence, private keys, raw personal data accidentally enter public audit or CI package | Bounded access and evidence references; synthetic fixtures only in tests. CI package contains approved artifacts/reports/hashes, never operational keys or databases |

## Executable baseline, not Genesis certification

| Evidence | Current coverage | Does not prove |
|---|---|---|
| `:persistence:test` — NumberedMigrationTest / MigrationCrashRecoveryTest | 33 dispatcher, transaction, contention and child-process interruption cases | Any actual schema-2 migration or Genesis policy |
| `:test:migrationAcceptance` | 49 historical/schema/recovery cases after this increment (previous 45 + 4 stopped-node restores) | Full arbitrary historical database compatibility or all device failures |
| `StoppedNodeRecoveryTest` | Runtime-signed and runtime-P10 profiles × DELETE/WAL; consistent backup, exact restore, signature/receipt checks, revoked/unknown old-key denial, pending P10 continuation | Live-node backup, fresh remote revocation discovery, hardware power loss, new multiparty settlement |
| Full `build` and existing CLI / two-node CI rehearsals | Regression evidence for current schema-1 behavior | ADR acceptance, adoption of the Constitution or authority to deploy |

The [CI evidence contract](core-genesis-mandate-recovery.md#ci-evidence-and-release-gates)
binds these results and the reviewed documents to a commit. Genesis rows remain
planned until their named tests and approved policy are supplied; a green baseline
must not be represented as 24 passing Genesis cases.
