plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":protocol"))
    // Ktor intentionally NOT added yet — ADR-007 baseline: "not tragged
    // into the first vertical slice without need". Add when a real
    // HTTPS transport adapter is implemented (post AC-13).
}
