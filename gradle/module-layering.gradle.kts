// Inspect Gradle's declared project dependencies, not source-code strings or regexes.
val verifyModuleLayering = tasks.register("verifyModuleLayering") {
    group = "verification"
    description = "Verify the ADR-007 production module graph and reject dependency cycles."
    doLast {
        val allowed = mapOf(
            ":domain" to emptySet(),
            ":application" to setOf(":domain"),
            ":protocol" to setOf(":domain", ":application"),
            ":persistence" to setOf(":domain", ":application"),
            ":security" to setOf(":domain", ":application"),
            ":sync" to setOf(":domain", ":application", ":protocol"),
            ":agent" to setOf(":domain", ":application"),
            ":api" to setOf(":domain", ":application", ":protocol", ":persistence", ":security", ":sync"),
            ":test" to rootProject.subprojects.map { it.path }.toSet() - ":test"
        )
        check(rootProject.subprojects.map { it.path }.toSet() == allowed.keys) {
            "New or removed modules require an explicit ADR-007 layering decision."
        }
        val graph = rootProject.subprojects.associate { module ->
            module.path to module.configurations.filter { it.name in setOf("compileClasspath", "runtimeClasspath") }
                .flatMap { configuration ->
                    configuration.allDependencies.withType<org.gradle.api.artifacts.ProjectDependency>()
                        .map { it.dependencyProject.path }
                }.toSet()
        }
        graph.forEach { (module, dependencies) ->
            check(dependencies.all { it in allowed.getValue(module) }) {
                "ADR-007 layering violation: $module -> ${dependencies - allowed.getValue(module)}"
            }
        }
        fun visit(module: String, path: Set<String>) {
            check(module !in path) { "Module dependency cycle: $path -> $module" }
            graph.getValue(module).forEach { visit(it, path + module) }
        }
        graph.keys.forEach { visit(it, emptySet()) }
        logger.lifecycle("ADR-007 production module graph verified.")
    }
}
subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(verifyModuleLayering) }
}
