plugins {
    alias(libs.plugins.kotlin.jvm)
    jacoco
}
kotlin { jvmToolchain(17) }
jacoco { toolVersion = libs.versions.jacoco.get() }
dependencies {
    api(libs.coroutines.core)
    testImplementation(libs.junit)
}
tasks.test { finalizedBy(tasks.jacocoTestReport) }
tasks.jacocoTestReport { reports { xml.required = true; html.required = true } }
// Mesmo gate de `:data` (ver lá): a checagem de entradas é uma tarefa própria para o gate não ser PULADO sem .exec.
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
