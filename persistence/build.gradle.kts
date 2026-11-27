import java.security.MessageDigest

plugins {
    kotlin("jvm")
    id("app.cash.sqldelight")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

sqldelight {
    databases {
        create("SotaOsDatabase") {
            packageName.set("sotaos.persistence.db")
            // Authoring output is separate from immutable verification inputs.
            schemaOutputDirectory.set(layout.buildDirectory.dir("schema-candidates"))
            verifyMigrations.set(true)
            verifyDefinitions.set(true)
        }
    }
}

// A missing snapshot must fail instead of making migration verification NO-SOURCE.
val verifyPinnedMigrationBaseline by tasks.registering {
    group = "verification"
    description = "Verify the immutable, empty version-one SQLDelight snapshot"
    val baseline = layout.projectDirectory.file("src/main/sqldelight/databases/1.db")
    inputs.file(baseline)
    doLast {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(baseline.asFile.readBytes()).joinToString("") { "%02x".format(it) }
        check(hash == "b226482970ac8122046cc1b8f0d0a04e72f4beac2dec0fc0abbea0d6d849ca18") {
            "Frozen SQLDelight 1.db changed; preserve the baseline and add a reviewed migration."
        }
    }
}

tasks.matching { it.name == "verifyMainSotaOsDatabaseMigration" }.configureEach {
    dependsOn(verifyPinnedMigrationBaseline)
}
tasks.named("check") { dependsOn(verifyPinnedMigrationBaseline) }
