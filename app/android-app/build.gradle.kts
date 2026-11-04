plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "br.dev.evaldo.meuscooter"
    compileSdk = 37
    defaultConfig {
        applicationId = "br.dev.evaldo.meuscooter"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-fase3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":ui"))
    implementation(project(":core-domain"))
    implementation(project(":core-protocol"))
    implementation(project(":data"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
