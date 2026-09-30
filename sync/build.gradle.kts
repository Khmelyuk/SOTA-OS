plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":protocol"))
    // HTTPS transport uses JDK HttpClient; hosting stays in api.
}
