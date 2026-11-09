// 总控（原 com.zongkong.app + com.zongkong.core）融合模块。
// 源码直接编译 D:/code/src-ref/zongkong；保留原包名，R/BuildConfig/serialization 由本模块生成。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.zongkong.app"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        // library 模块默认不生成 VERSION_NAME/CODE，总控 UI 与健康协议要用
        buildConfigField("String", "VERSION_NAME", "\"0.17.0-court\"")
        buildConfigField("int", "VERSION_CODE", "20")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    sourceSets.getByName("main") {
        java.srcDirs(legacySrc("zongkong", "app/src/main/java"))
        res.srcDirs(legacySrc("zongkong", "app/src/main/res"))
    }
    sourceSets.getByName("test") {
        java.srcDirs(legacySrc("zongkong", "app/src/test/java"))
        // 模拟 Notion 服务器：单元测试与模拟器端到端共用
        java.srcDirs(legacySrc("zongkong", "app/src/sharedTest/java"))
    }
    packaging {
        resources.excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md")
    }
}

dependencies {
    implementation(project(":infra"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // :infra 的客户端用 org.json；android.jar 里的只是桩（上面开了 isReturnDefaultValues）
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

/** 仓库内 legacy/ 优先；否则 -PlegacyRoot / LEGACY_ROOT，最后回落到 D:/code/src-ref。 */
fun legacySrc(project: String, path: String): java.io.File {
    val local = file("$rootDir/legacy")
    val root = (findProperty("legacyRoot") as String?)
        ?: System.getenv("LEGACY_ROOT")
        ?: if (local.isDirectory) local.absolutePath else "D:/code/src-ref"
    return file("$root/$project/$path")
}
