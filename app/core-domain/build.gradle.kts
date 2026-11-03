plugins {
    alias(libs.plugins.kotlin.jvm)
    jacoco
}
kotlin { jvmToolchain(17) }
jacoco { toolVersion = libs.versions.jacoco.get() }
dependencies {
    api(project(":core-protocol"))
    testImplementation(project(":data"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
tasks.test { finalizedBy(tasks.jacocoTestReport) }
tasks.jacocoTestReport { reports { xml.required = true; html.required = true } }
val coverageGate = tasks.register<JacocoCoverageVerification>("coverageGate") {
    dependsOn(tasks.test)
    executionData(tasks.test.get())
    classDirectories.setFrom(sourceSets.main.get().output)
    violationRules { rule { limit { counter = "LINE"; minimum = "0.80".toBigDecimal() } } }
}
tasks.check { dependsOn(coverageGate) }
