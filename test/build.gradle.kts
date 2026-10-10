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

// Disposable fixture host using real runtimes and a separate HTTPS CLI process.
tasks.register<JavaExec>("twoNodeCoreLoop") {
    group = "verification"
    description = "Rehearse signed Core Loop, strict two-node HTTPS recovery, and P10 exit"
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sotaos.test.rehearsal.TwoNodeCoreLoopKt")
}
