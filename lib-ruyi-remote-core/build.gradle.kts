plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.xxt.zl.ai.ruyiremote"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // core 层的默认服务地址：源仓 app 曾把这两个常量放 application；拷入库后
        // 由库提供默认值，独立 App 壳或宿主可覆盖（见 ruiyi-remote 的构建期注入）。
        val loginOrigin = (project.findProperty("ruiyiLoginOrigin") as String?)
            ?: "https://ai.thchengtay.com/aiserver"
        val relayDefault = (project.findProperty("relayDefault") as String?)
            ?: "https://118.196.18.181:8443"
        buildConfigField("String", "RUYI_LOGIN_ORIGIN", "\"$loginOrigin\"")
        buildConfigField("String", "RELAY_SERVER_URL", "\"$relayDefault\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    // Noise 的握手、分片编解码、协议帧都是纯 JVM 逻辑，单元测试不需要设备。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // X25519 走 Bouncy Castle 的轻量 API：JCA 的 X25519 要 API 33+，而 minSdk 是 29。
    implementation(libs.bouncycastle)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.serialization.json)
}