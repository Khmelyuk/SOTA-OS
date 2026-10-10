# P01 local credential lifecycle

The local-passphrase provider now supports real self-service rotation and
revocation. It updates the same `local_credential` verifier used by authentication;
there is no second credential store. Person, Identity and provider bindings survive
both operations. A revoked handle stays reserved and cannot be enrolled again.

```sh
./gradlew :api:installDist
api/build/install/api/bin/api auth create --handle owner --db ./node.db
api/build/install/api/bin/api auth rotate --handle owner --db ./node.db
api/build/install/api/bin/api auth revoke --handle owner --db ./node.db
```

Use `--unit UNIT` consistently if not using the default `local` unit. Passwords are
read from the terminal with echo disabled; rotation asks for the current passphrase
and the new one twice. Revocation requires `ВІДКЛИКАТИ owner` confirmation and the
current passphrase. Identifiers alone never authorize a lifecycle change.

`api.identity.P01CredentialsRuntime` wires the existing AuthenticationService,
local provider, RightsConstraint and SQLite mutation adapter. Challenges are
one-use, short-lived and scoped to their runtime and unit. The subject and revision
come from successful proof verification, never from a caller-supplied Person ID.
Each mutation checks the current revision and owner again within its transaction.
Verification also rechecks the active revision after password hashing; failed
attempts against an old revision cannot lock a replacement credential.

The additive migration preserves existing hashes, salts, bindings and lockout
state. The current verifier carries `revision` and `revoked_at`. Append-only
`local_credential_audit` records ENROLL, ROTATE and REVOKE transitions without
secrets or old verifier copies. ROTATE/REVOKE name the authenticated Person.
ENROLL remains trusted-operator bootstrap and has no authenticated actor claim.
LEGACY_BASELINE records imported state and its stored creation time, not proof of
who originally enrolled it. A failed audit insertion rolls back the mutation.

The former value-only `IdentityProtocol.rotateCredential/revokeCredential` methods
were removed: they could neither verify a proof nor mutate authentication state.
Hosts should use P01CredentialsRuntime, or compose LocalCredentialLifecycleService
with authentication and mutation ports. The legacy value-only `authenticate`
method remains deprecated and always denies.

Scope: this implements the existing local provider's lifecycle. General Account
persistence/status transitions, multiple credentials per Account, external-provider
rotation, recovery after loss of every credential and invalidation of already
issued sessions remain open. A returned AuthenticatedSession is a proof result,
not a live, revocable session token. This does not rotate actor signing keys or
peer bearer tokens, whose governed lifecycle is separate. P01 is not fully complete.

Evidence: PersonAccountIsolationTest exercises actual login after SQLite reopen,
lockout, rights denial, proof replay, unit/runtime isolation and audit rollback.
CredentialRaceTest covers stale proof and audit immutability; CredentialMigrationTest
covers existing databases. `scripts/p01-credential-cli-smoke.py` exercises the
terminal commands and secret exclusion in CI.

A replacement passphrase must differ from the proof just authenticated. After
migration, use lifecycle-aware binaries: older versions do not enforce the added
revocation field. Migration is additive, not a safe downgrade path.
