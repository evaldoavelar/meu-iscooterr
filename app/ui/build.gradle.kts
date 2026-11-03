plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    jacoco
}

android {
    namespace = "br.dev.evaldo.meuscooter.ui"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { debug { enableUnitTestCoverage = true } }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Robolectric carrega classes em classloader próprio: sem isso o JaCoCo não enxerga os Composables.
            all { test ->
                test.extensions.configure<JacocoTaskExtension> {
                    isIncludeNoLocationClasses = true
                    excludes = listOf("jdk.internal.*")
                }
            }
        }
    }
}
kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core-domain"))
    api(project(":core-protocol"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)

    testImplementation(project(":data"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
}

// ---- Cobertura (decisão do proprietário: ≥ 80 % de linhas na lógica da UI; build falha abaixo) ----
jacoco { toolVersion = libs.versions.jacoco.get() }
val uiExec = layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
val uiClasses = layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
val generated = listOf("**/R.class", "**/R$*.class", "**/BuildConfig.class", "**/*Preview*", "**/ComposableSingletons*")

// Lógica (mapper, estado, ViewModel): pacote raiz `ui`, sem subpacotes.
val logicTree = fileTree(uiClasses) { include("br/dev/evaldo/meuscooter/ui/*.class"); exclude(generated) }
// Composables medidos e reportados (sem gate); tema/ícones fora.
val composableTree = fileTree(uiClasses) { include("br/dev/evaldo/meuscooter/ui/home/**"); exclude(generated) }

tasks.register<JacocoReport>("uiCoverageReport") {
    group = "verification"
    dependsOn("testDebugUnitTest")
    executionData(uiExec)
    classDirectories.setFrom(logicTree)
    reports { xml.required = true; html.required = true; html.outputLocation = layout.buildDirectory.dir("reports/coverage/logic") }
}
tasks.register<JacocoReport>("composableCoverageReport") {
    group = "verification"
    dependsOn("testDebugUnitTest")
    executionData(uiExec)
    classDirectories.setFrom(composableTree)
    reports { xml.required = true; html.required = true; html.outputLocation = layout.buildDirectory.dir("reports/coverage/composables") }
}
val coverageGate = tasks.register<JacocoCoverageVerification>("coverageGate") {
    group = "verification"
    dependsOn("testDebugUnitTest")
    executionData(uiExec)
    classDirectories.setFrom(logicTree)
    violationRules { rule { limit { counter = "LINE"; minimum = "0.80".toBigDecimal() } } }
}
tasks.named("check") { dependsOn(coverageGate, "composableCoverageReport") }
