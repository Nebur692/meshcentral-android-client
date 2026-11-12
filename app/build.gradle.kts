/*
 * MeshCentral Android Client: the technician's app for any MeshCentral server. Package fixed for ever once on Google
 * Play: io.github.nebur692.meshclient. / La app del técnico para cualquier MeshCentral. Paquete fijo para siempre.
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.nebur692.meshclient"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.nebur692.meshclient"
        // Android 10 and newer; what each newer Android adds is used where it exists (Material You 12, themed icon and
        // per-app language 13, predictive back 14). / Android 10 o más nuevo; lo que trae cada versión se usa donde existe.
        minSdk = 29
        targetSdk = 37
        versionCode = 10
        versionName = "0.8.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // The per-app language list (Android 13+ Settings), made from the translations there are.
        // La lista de idiomas de la app (Ajustes de Android 13+), hecha con las traducciones que hay.
        generateLocaleConfig = true
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
    }
}

dependencies {
    implementation(libs.meshclient.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.core.ktx)
    implementation(libs.splashscreen)
    implementation(libs.webkit)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
