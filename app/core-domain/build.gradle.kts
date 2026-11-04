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
// JacocoReportBase tem `onlyIf { há .exec }`: sem execução o gate seria PULADO (verde). A checagem fica numa tarefa
// própria, exigida pelo gate antes do `onlyIf` (revisão Codex r1, achado 2).
val verifyCoverageInputs = tasks.register("verifyCoverageInputs") {
    group = "verification"
    dependsOn(tasks.test)
    doLast {
        val exec = layout.buildDirectory.file("jacoco/test.exec").get().asFile
        check(exec.exists()) { "coverageGate: $exec ausente (os testes não geraram dados de cobertura)" }
        check(!sourceSets.main.get().output.asFileTree.isEmpty) { "coverageGate: nenhuma classe compilada em ${sourceSets.main.get().output.classesDirs.asPath}" }
    }
}
val coverageGate = tasks.register<JacocoCoverageVerification>("coverageGate") {
    dependsOn(verifyCoverageInputs)
    executionData(tasks.test.get())
    classDirectories.setFrom(sourceSets.main.get().output)
    violationRules { rule { limit { counter = "LINE"; minimum = "0.80".toBigDecimal() } } }
}
tasks.check { dependsOn(coverageGate) }
