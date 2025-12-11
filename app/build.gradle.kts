import java.io.FileInputStream
import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val useKeystoreProperties = keystorePropertiesFile.canRead()
val keystoreProperties = Properties()
if (useKeystoreProperties) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

android {
    if (useKeystoreProperties) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                enableV4Signing = true
            }
        }
    }

    namespace = "app.grapheneos.speechservices"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.grapheneos.speechservices"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = versionCode.toString()

        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en")
        noCompress += "onnx"
    }

    packaging {
        resources.merges.add("META-INF/DEPENDENCIES")
        resources.merges.add("META-INF/INDEX.LIST")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (useKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
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
    implementation(libs.onnxruntime.android)
    implementation(libs.androidx.media3.common.ktx)
//    implementation(libs.apache.opennlp.tools)
    implementation(libs.slf4j.nop)
    // TODO: Remove and use the official build once OpenNLP 2.5.7 is released with the fix for Android.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.icu4j)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}