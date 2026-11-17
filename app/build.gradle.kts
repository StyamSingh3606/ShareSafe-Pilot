plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.sharesafe.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sharesafe.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        val endpoint = providers.gradleProperty("shareSafeBaseUrl").orElse("").get()
        require(endpoint.isEmpty() || Regex("https://[A-Za-z0-9.-]+").matches(endpoint)) { "Use the public HTTPS origin without a trailing slash" }
        manifestPlaceholders["shareSafeLinkHost"] = endpoint.removePrefix("https://").ifEmpty { "sharesafe.invalid" }
        buildConfigField("String", "API_URL", "\"$endpoint\"")
    }
    buildTypes { debug { buildConfigField("String", "API_URL", "\"http://127.0.0.1:8787\"") } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
}
