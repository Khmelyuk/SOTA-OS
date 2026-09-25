// Root build. Fixes versions in one place (ADR-007 source doc §8/§9:
// reproducible build — LOCAL == CI == CLEAN ENVIRONMENT).

import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    kotlin("jvm") version "2.0.21" apply false
    id("app.cash.sqldelight") version "2.0.2" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
}

allprojects {
    group = "sotaos"
    version = "0.1.0-mvp"

    // NOTE: repositories are declared centrally in settings.gradle.kts
    // (dependencyResolutionManagement, mode = FAIL_ON_PROJECT_REPOS).
    // Declaring `repositories {}` here as well is a hard conflict —
    // found by inspection before any build was run; fixed here.
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    // NOTE: plugin applied dynamically via apply(plugin = "...") above,
    // not via plugins {} in this exact build file — so the type-safe
    // `kotlin { }` accessor extension function is NOT generated here.
    // Must configure the extension by type instead. Found and fixed
    // during the first real ./gradlew run (2026-09-23).
    // Same lesson as the Kotlin extension above: detekt is applied via
    // apply(plugin = "...") here, so its type-safe `detekt { }`
    // accessor is not generated — configure by type instead. Found in
    // the same build run that surfaced the 21 default-threshold
    // issues below (2026-09-24).
    extensions.configure<DetektExtension> {
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
    }

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21) // LTS JVM runtime, ADR-007 source doc §9
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    dependencies {
        "testImplementation"("io.kotest:kotest-runner-junit5:5.9.1")
        "testImplementation"("io.kotest:kotest-assertions-core:5.9.1")
        "testImplementation"("io.kotest:kotest-property:5.9.1")
    }
}

