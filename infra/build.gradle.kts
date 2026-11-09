plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// 三端共用的对外能力（LLM / 后续的语音、通知、提醒）。
// ys、zk 是 library 且被 app 依赖，所以统一实现不能放 app，只能放这里。
android {
    namespace = "com.changliu.infra"
    compileSdk = 34

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // OkHttpClient 出现在 ChatClient 的公开签名上，必须 api 才能被下游直接引用
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
