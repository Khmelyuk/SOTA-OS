rootProject.name = "sota-os"

// Module layout per ADR-007 (Build & Test Toolchain), matching the
// authoritative "SOTA OS — ADR-007 — Build & Test Toolchain" spec §4:
// domain / application / protocol / persistence / security / sync / agent / api / test
include(":domain")
include(":application")
include(":protocol")
include(":persistence")
include(":security")
include(":sync")
include(":agent")
include(":api")
include(":test")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}
