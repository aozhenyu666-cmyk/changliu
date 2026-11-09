// 一手（原 com.yishou.app）融合模块。
// 源码不复制，直接编译 D:/code/src-ref/yishou 的源树；保留原包名，R/BuildConfig 由本模块生成。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.yishou.app"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        // 一手原 flavor 的字段（融合先走 mainline 行为，见实施计划 §6.4）
        buildConfigField("boolean", "CHALU", "false")
        buildConfigField("String", "CHALU_NAME", "\"\"")
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
        java.srcDirs(legacySrc("yishou", "app/src/main/java"))
        res.srcDirs(legacySrc("yishou", "app/src/main/res"))
    }
    sourceSets.getByName("test") {
        java.srcDirs(legacySrc("yishou", "app/src/test/java"))
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":infra"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 一手仍用 MasterKeys API，固定在 1.0.0
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
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
