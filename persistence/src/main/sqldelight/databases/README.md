# Frozen SQLDelight migration-verification input

`1.db` is an **empty generated-schema fixture**, not a node database or a deployment
template. It was generated from the unchanged declarations at source revision
`08460f88491c475357373686aa03220839097200` using SQLDelight 2.0.2 on JDK 21.

- SHA-256: `b226482970ac8122046cc1b8f0d0a04e72f4beac2dec0fc0abbea0d6d849ca18`
- Size: 335872 bytes; SQLite `user_version = 0` as emitted by the generator.
- Contents: 38 empty application tables, two explicit indexes and ten declared triggers.
- The filename identifies SQLDelight schema version 1. It does not mark completion
  of `SqlDelightStore` initialization or include its 41 additional runtime guards.

`verifyPinnedMigrationBaseline` rejects deletion or changed bytes.
`verifyMainSotaOsDatabaseMigration` consumes this file and is included in `check`.
`SqlDelightBaselineTest` verifies disposable copies for emptiness, exact declared
catalog, integrity/FKs, preservation of a synthetic row and full runtime completion.

Generate **candidates**, without overwriting this frozen input:

```bash
./gradlew :persistence:generateMainSotaOsDatabaseSchema --console=plain
```

Output goes to `persistence/build/schema-candidates/`. Generation is an explicit
authoring step; it is not a dependency of migration verification. The initial
candidate was reviewed against the pinned catalog and then copied here once.
Retain `1.db` when adding future migrations; do not regenerate it to silence a diff.
Only this exact empty fixture is excepted from the repository's `*.db` ignore rule.

See [verification and ordering](../../../../../docs/sqlite-migration-verification.md)
and the [full runtime inventory](../../../../../docs/sqlite-schema-inventory.md).
