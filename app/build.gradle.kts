import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

// 签名信息的来源，按优先级：
//   1. Gradle 属性 RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
//      （可以写在 ~/.gradle/gradle.properties 里，也可以 -P 传入，或用同名环境变量）
//   2. 仓库根目录的 keystore.properties（不进版本库）
// 两者都没有时 release 包不签名，但仍然可以编译。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

fun signingValue(gradleName: String, fileKey: String): String? {
    val value = providers.gradleProperty(gradleName).orNull
        ?: System.getenv(gradleName)
        ?: keystoreProps.getProperty(fileKey)
    return value?.takeIf { it.isNotBlank() }
}

val releaseStoreFile = signingValue("RELEASE_STORE_FILE", "storeFile")
val releaseStorePassword = signingValue("RELEASE_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("RELEASE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("RELEASE_KEY_PASSWORD", "keyPassword")

android {
    namespace = "com.sallee.whalechat"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.sallee.whalechat"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.9.0")
}
