plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")          // 一手/总控的 Compose UI
    id("com.google.devtools.ksp")                       // 一手 Room
    id("org.jetbrains.kotlin.plugin.serialization")     // 总控 kotlinx-serialization
}

android {
    namespace = "com.changliu.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.changliu.app"
        minSdk = 26 // 播放状态回调（AudioPlaybackCallback）要 Android 8.0
        targetSdk = 34
        versionCode = 7
        versionName = "0.7.0-fusion"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 一手 mainline/chalu flavor 暂不引入（实施计划 §6.4），统一走 mainline 行为
        buildConfigField("boolean", "CHALU", "false")
        buildConfigField("String", "CHALU_NAME", "\"\"")
    }

    signingConfigs {
        // 固定的调试签名，提交在仓库里：三方一致，每次打出的 APK 直接覆盖安装，旧数据不丢
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
        // Curbox 的 AIDL 接口不再使用
        aidl = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1,LICENSE*}",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md",
        )
    }
    lint {
        // 调试版只要能装能跑；lint 的警告不挡打包
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// 一手 Room 迁移基线
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ys"))
    implementation(project(":zk"))

    // Compose（一手/总控同版本）
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Room（一手 33 实体 / 15 段迁移）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 加密设置 / 后台 / 网络 / 序列化
    // 一手仍用 MasterKeys API，所以固定在 1.0.0（1.1.x 已移除该 API）
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // 用 Shizuku 的 shell 身份把无障碍写回去（抄 Curbox 的同一条路：IShizukuService.newProcess）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // 单元测试 / Room 迁移验证
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
