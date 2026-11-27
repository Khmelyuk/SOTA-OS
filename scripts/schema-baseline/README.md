# Baseline-one catalog capture

`CaptureCatalog.java` is an authoring probe, not a production tool or test dependency.
It opens only an isolated temporary SQLite connection (`jdbc:sqlite:`), captures
generated declarations, initializes `SqlDelightStore`, captures the completed runtime
and closes the driver. It accepts no database path. Output is UTF-8 TSV:
stage, object type, name, owning table and Base64-encoded exact DDL text.

The pinned [v1 manifest](../../test/src/test/resources/schema-baseline/v1.json) was
captured from production classes built at
`430bde9a19c3690e843a5ecbe6f54e3c3fd6563c`, before any schema edits in this increment.
It records SHA-256 for the persistence Kotlin and `.sq` sources at that revision.
No user rows, private keys or live database files were read or included.

To reproduce the catalog, build that revision in a separate checkout with JDK 21.
Run its `./gradlew :api:installDist --console=plain`. From that checkout, use an
absolute path to this probe in the current checkout:

```bash
/usr/lib/jvm/java-21-openjdk-amd64/bin/java \
  -cp 'api/build/install/api/lib/*' \
  /path/to/current/sota-os/scripts/schema-baseline/CaptureCatalog.java \
  > /tmp/sota-baseline-v1.tsv
```

Decode each line's fifth field as Base64 UTF-8 without trimming or normalizing SQL.
The runtime stage's ordered `[type, name, table, sql]` rows must equal the manifest's
`catalog`. The generated stage has the same tables/indexes and exactly the ten
`declaredTriggerNames`; the 41 remaining names are `runtimeInstalledTriggerNames`.
The source fingerprints can be checked against the isolated checkout independently.

The separate [historical variants](../../test/src/test/resources/schema-baseline/historical-ddl-variants.json)
record the exact six DDL differences found by opening the existing pinned historical
profiles with this runtime: five in `durable-p09`, one in `knowledge-versions`.
They were reviewed against the frozen source declarations and compatibility installers;
only formatting and SQL keyword case differ. Captured runtime profiles have no variants.

`CanonicalSchemaTest` pins both resource hashes. Ordinary CI never runs this probe,
needs no Git history and never regenerates expected catalogs. Keep baseline-one
resources immutable: an intended schema change needs a reviewed version/transition
contract and a new baseline, not automatic replacement of these expected bytes.
