plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.xxt.zl.ai.ruyiremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xxt.zl.ai.ruyiremote"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 两个地址都要能在构建期替换，否则本地联调只能改源码：
        //   ./gradlew :app-ruyi-remote:assembleDebug \
        //     -PruiyiLoginOrigin=http://10.0.2.2:8890/aiserver \
        //     -PrelayDefault=http://10.0.2.2:8899
        // 默认值是现网，不传就与以前完全一致。
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
        compose = true
        buildConfig = true
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":lib-ruyi-remote-ui"))
    implementation(project(":lib-ruyi-remote-core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}