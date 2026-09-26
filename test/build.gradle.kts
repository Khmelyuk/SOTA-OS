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
    testImplementation("app.cash.sqldelight:sqlite-driver:2.0.2")
}
