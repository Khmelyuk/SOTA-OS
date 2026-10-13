plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
