import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 起 Kotlin 由 Android 插件内置，不再需要 org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * 后端地址：编译期默认值，运行时仍可在 App 内「服务器设置」修改（按服务器分别保存登录态）。
 *  -PZHIXUE_DEBUG_URL / -PZHIXUE_RELEASE_URL 覆盖，local.properties 里写同名键也可以。
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun cfg(key: String, fallback: String): String =
    (providers.gradleProperty("ZHIXUE_$key").orNull ?: localProps.getProperty("ZHIXUE_$key")) ?: fallback

val debugBaseUrl = cfg("DEBUG_URL", "http://10.0.2.2:8080")
val releaseBaseUrl = cfg("RELEASE_URL", "https://101.37.65.217")
// 自托管 HTTP 部署（还没有证书）时用 -PZHIXUE_RELEASE_ALLOW_HTTP=true 打开明文流量
val releaseAllowHttp = cfg("RELEASE_ALLOW_HTTP", "false").toBoolean()

android {
    namespace = "com.zhixue.assistant"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zhixue.assistant"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            manifestPlaceholders["networkSecurityConfigRes"] = "network_security_config_permissive"
            buildConfigField("String", "DEFAULT_BASE_URL", "\"$debugBaseUrl\"")
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["networkSecurityConfigRes"] =
                if (releaseAllowHttp) "network_security_config_permissive" else "network_security_config_secure"
            buildConfigField("String", "DEFAULT_BASE_URL", "\"$releaseBaseUrl\"")
            buildConfigField("boolean", "ALLOW_CLEARTEXT", releaseAllowHttp.toString().lowercase())
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
}
