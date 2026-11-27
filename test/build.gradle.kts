plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":protocol"))
    implementation(project(":persistence"))
    implementation(project(":security"))
    implementation(project(":sync"))
    implementation(project(":agent"))
    testImplementation(project(":api"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("app.cash.sqldelight:sqlite-driver:2.0.2")
}

// Test only disposable copies of the same binary snapshot consumed by SQLDelight.
tasks.processTestResources {
    from(project(":persistence").layout.projectDirectory.file("src/main/sqldelight/databases/1.db")) {
        into("schema-baseline/sqldelight")
    }
}

// The same frozen-profile tests also run in the ordinary test task.
tasks.register<Test>("migrationAcceptance") {
    group = "verification"
    description = "Verify pinned historical upgrades, preservation, opening failures and retry"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("sotaos.test.migration.*") }
    shouldRunAfter(tasks.test)
}

// Disposable fixture host using real runtimes and a separate HTTPS CLI process.
tasks.register<JavaExec>("twoNodeCoreLoop") {
    group = "verification"
    description = "Rehearse signed Core Loop, strict two-node HTTPS recovery, and P10 exit"
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sotaos.test.rehearsal.TwoNodeCoreLoopKt")
}

tasks.register<JavaExec>("tlsRollback") {
    group = "verification"
    description = "Rehearse failed TLS replacement and previous-bundle recovery through real CLI hosts"
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sotaos.test.rehearsal.TlsRollbackKt")
}

tasks.register<JavaExec>("tlsRollout") {
    group = "verification"
    description = "Rehearse overlapping TLS trust, staged peer replacement and retirement of old trust"
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sotaos.test.rehearsal.TlsRolloutKt")
}
