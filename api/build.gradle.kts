plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":protocol"))
    implementation(project(":persistence"))
    implementation(project(":security"))
    implementation(project(":sync"))
    implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
}

application {
    mainClass.set("sotaos.api.cli.MainKt")
}

// This first executable is a local CLI. Ktor remains deferred until a
// network API server is an actual MVP requirement (ADR-007).
