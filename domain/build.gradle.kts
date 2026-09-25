plugins {
    kotlin("jvm")
}

// LAYERING RULE (ADR-007 / Master Prompt): domain has ZERO dependency on
// application, protocol, persistence, security, sync, agent, api.
// If this ever needs a `project(":...")` dependency, that is an
// architecture defect, not a build-config fix.
dependencies {
    // test-only deps inherited from root subprojects{} block
}
