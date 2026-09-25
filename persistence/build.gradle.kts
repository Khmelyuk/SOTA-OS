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
        }
    }
}
