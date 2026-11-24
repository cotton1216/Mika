import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 关于页上的构建编号:当前提交的短哈希;没有 git(如从源码包编译)时写 dev
val gitHash: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD") }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: "dev"

// 版本号单一来源：defaultConfig 与发布产物文件名共用，升版只改这一处。
val ruyiVersionName = "0.1.54"
val ruyiVersionCode = 55

// 更新 feed 地址由构建注入：debug 默认本地，release/perf 必须由 -PupdateFeedUrl 指定。
// 生产地址形如 https://ai.thchengtay.com/desktop/xxt/ruyi/android/latest.json，本地联调指向本地静态文件服务。
val defaultUpdateFeedUrl = "http://127.0.0.1:3100/ruyi/android/latest.json"
val updateFeedUrl: String? = providers.gradleProperty("updateFeedUrl").orNull
    ?.trim()
    ?.trimEnd('/')
    ?.takeIf { it.isNotEmpty() }
if (updateFeedUrl == null &&
    gradle.startParameter.taskNames.any { Regex("(?i)(perf|release)").containsMatchIn(it) }
) {
    throw GradleException("出 release/perf 包必须传 -PupdateFeedUrl=<静态 feed 地址>（例如 https://ai.thchengtay.com/desktop/xxt/ruyi/android/latest.json）")
}

// 发布签名：读 app/release-signing.properties（不入库）。缺失时 release 回退到调试签名，避免构建失败。
val releaseSigning = file("release-signing.properties")
    .takeIf { it.exists() }
    ?.let { Properties().apply { it.inputStream().use { s -> load(s) } } }

android {
    namespace = "com.wangzan101.mika"
    compileSdk = 36

    signingConfigs {
        create("release") {
            releaseSigning?.let {
                storeFile = file(it.getProperty("storeFile"))
                storePassword = it.getProperty("storePassword")
                keyAlias = it.getProperty("keyAlias")
                keyPassword = it.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.zl.ruyiai.yuan"
        // 29: 兼容 Android 10 老机型（如 Redmi 10X 4G）；Android 11+ 专属 API 均有 SDK_INT 版本守卫
        minSdk = 29
        targetSdk = 35
        versionCode = ruyiVersionCode
        versionName = ruyiVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        // 关于页版本号后的 Beta 标记;出正式版时改成 false
        buildConfigField("boolean", "BETA", "false")
        buildConfigField("boolean", "ADB_LOGS", "false")
    }

    // 沙箱程序只有 arm64 版本(jniLibs/arm64-v8a);APK 不限 ABI,别的设备照常安装、显示「此设备不支持沙箱」。
    // 沙箱程序要从 nativeLibraryDir 直接执行,安装时必须解到磁盘
    packaging { jniLibs { useLegacyPackaging = true } }

    // 发布用的包(perf、release)按架构再出一份:除了全架构包,另出只带 arm64-v8a 原生库的小包。
    // 两个包代码、资源、版本号、签名都一样,只差 lib/ 下的其他架构;各自是完整 APK,不是一起安装的拆分包。
    // 产物:如意 AI-universal-<类型>.apk、如意 AI-arm64-v8a-<类型>.apk。拆分对所有构建类型生效,
    // 所以只在这次构建要出 perf/release 包时打开,单独构建调试包时产物仍是 app-debug.apk。
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { Regex("(?i)(perf|release)").containsMatchIn(it) }
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }
    // 自带的运行环境(assets/runtime/rootfs.tar.xz,tools/sandbox/build-runtime.py 生成)已是 xz,不再压一遍
    androidResources { noCompress += "xz" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 评测任务集(仓库根目录 eval/tasks)打进测试包的 assets,真机评测跑器从那里读题
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("eval/tasks"))

    buildTypes {
        // 正式发布用 ruyi 签名；无签名配置时回退调试签名。
        getByName("release") {
            signingConfig = if (releaseSigning != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        // 真机验收时并排安装,保留发行版和用户数据;默认构建标识不变。
        if (providers.gradleProperty("devInstall").isPresent) getByName("debug") {
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "如意 AI Dev")
        }
        // 看流畅度用的包:和发布版一样不可调试(调试包不做预先编译,界面慢好几倍,手感不作数),
        // 用本机调试签名,能直接覆盖调试包、保留数据;可被性能工具采样。./gradlew :app-main:assemblePerf
        create("perf") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isProfileable = true
            matchingFallbacks += "release"
        }
        // 侧装用:官方发布包是别处的密钥签名,自己编的包盖不上去。加包名后缀装成第二个 App,
        // 原包和数据都不动。./gradlew :app-main:assemblePerf -PsideInstall
        if (providers.gradleProperty("sideInstall").isPresent) getByName("perf") {
            applicationIdSuffix = ".perf"
            resValue("string", "app_name", "如意 AI 测试版")
        }
        // 本地日志检查包：并排安装，允许 adb run-as，首次启动即记录详细 Trace。
        create("logs") {
            initWith(getByName("perf"))
            applicationIdSuffix = ".logs"
            versionNameSuffix = "-logs"
            isDebuggable = true
            isProfileable = false
            resValue("string", "app_name", "如意 AI Logs")
            buildConfigField("boolean", "ADB_LOGS", "true")
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":lib-core"))
    // 如意远程(ruiyi-remote-native)拆出的界面库:右侧套进 RemoteHome,靠独立包名与上游 core/remote 隔离
    implementation(project(":lib-ruyi-remote-ui"))
    implementation(libs.protobuf.lite)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xz)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    // 两种识别模型都随 APK 安装；首次使用不依赖 Play 服务下载。
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)
    // 微信绑定二维码只在本机编码，不调用在线图片服务。
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // 沙箱路径契约(core 的 testFixtures):AndroidSandbox 在 JVM 与真机上都跑同一套
    testImplementation(testFixtures(project(":lib-core")))
    androidTestImplementation(testFixtures(project(":lib-core")))

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.junit)
}

// LicensesTest 读仓库的 licenses/THIRD_PARTY_NOTICES.md:让它参与测试的增量判断
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("licenses/THIRD_PARTY_NOTICES.md")).withPropertyName("thirdPartyNotices")
}

// 发布包文件名带上品牌、版本与架构：如意 AI-0.1.40-arm64-v8a-release.apk / 如意 AI-0.1.40-universal-release.apk。
// 调试包保持 app-debug.apk 不变，脚本与真机验收流程仍按原名引用。
androidComponents {
    onVariants { variant ->
        variant.buildConfigFields.put(
            "UPDATE_FEED_URL",
            com.android.build.api.variant.BuildConfigField(
                "String", "\"${updateFeedUrl ?: defaultUpdateFeedUrl}\"", "静态更新 feed 地址",
            ),
        )
        if (variant.buildType == "debug") return@onVariants
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier
                ?: "universal"
            (output as com.android.build.api.variant.impl.VariantOutputImpl)
                .outputFileName.set("如意 AI-$ruyiVersionName-$abi-${variant.buildType}.apk")
        }
    }
}
