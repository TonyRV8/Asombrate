import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

fun readConfig(name: String, defaultValue: String): String {
    val envValue = System.getenv(name)?.trim().orEmpty()
    if (envValue.isNotEmpty()) {
        return envValue
    }
    return localProperties.getProperty(name, defaultValue).trim()
}

val placeholderReleaseHosts = listOf(
    "your-backend.example.com",
    "tu-backend-publico.com"
)

val backendBaseUrlDebug = readConfig(
    "BACKEND_BASE_URL_DEBUG",
    readConfig("BACKEND_BASE_URL", "http://10.0.2.2:8081/")
)

val backendBaseUrlRelease = readConfig(
    "BACKEND_BASE_URL_RELEASE",
    "https://tu-backend-publico.com/"
)

val appVersionCode = readConfig("APP_VERSION_CODE", "1").toIntOrNull()
    ?: throw GradleException("APP_VERSION_CODE debe ser numerico.")
val appVersionName = readConfig("APP_VERSION_NAME", "1.0.0")

val releaseTasksRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
if (releaseTasksRequested) {
    val invalidReleaseUrl =
        backendBaseUrlRelease.isBlank() ||
            placeholderReleaseHosts.any { backendBaseUrlRelease.contains(it, ignoreCase = true) } ||
            !backendBaseUrlRelease.startsWith("https://") ||
            backendBaseUrlRelease.contains("10.0.2.2") ||
            backendBaseUrlRelease.contains("localhost", ignoreCase = true) ||
            backendBaseUrlRelease.contains("127.0.0.1")

    if (invalidReleaseUrl) {
        throw GradleException(
            "BACKEND_BASE_URL_RELEASE debe apuntar a tu backend HTTPS real para builds release. " +
                "Configuralo en local.properties."
        )
    }
}

android {
    namespace = "com.example.asombrate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.asombrate"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrlDebug\"")
            buildConfigField("boolean", "INTERNAL_TEST_BUILD", "true")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrlRelease\"")
            buildConfigField("boolean", "INTERNAL_TEST_BUILD", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        create("releaseTest") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            versionNameSuffix = "-release-test"
            buildConfigField("boolean", "INTERNAL_TEST_BUILD", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = true
        abortOnError = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)

    // Solar Calculation
    implementation("org.shredzone.commons:commons-suncalc:3.5")
    implementation(libs.spotbugs.annotations)

    // Maps - OpenStreetMap
    implementation(libs.osmdroid)

    // Location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
