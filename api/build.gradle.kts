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

// CLI and bounded JDK HTTPS pilot host; no general web framework is needed yet.
