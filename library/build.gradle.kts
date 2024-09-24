plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
//    alias(libs.plugins.compose.compiler)
}

val buildSdkVersion: Int by rootProject.extra
//val composeCompilerVersion: String by rootProject.extra
val minimumSdkVersion: Int by rootProject.extra

android {
    namespace = "jp.co.cyberagent.android.gpuimage"
    compileSdk = buildSdkVersion

    defaultConfig {
        minSdk = minimumSdkVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }

        externalNativeBuild {
            cmake { cppFlags.add("") }
        }
    }

    externalNativeBuild {
        cmake { path("src/main/cpp/CMakeLists.txt") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }
}

dependencies {
    // AndroidX
    implementation(libs.bundles.androidx)
    implementation("com.vanniktech:android-image-cropper:4.6.0")
}