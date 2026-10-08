plugins {
    alias(libs.plugins.android.library)
}

// v2.2.1 虚拟屏:契约模块(AIDL + 常量)。
// 故意全部用 Java:服务端模块的 dex 不能带 kotlin-stdlib(单 classes.dex 限制),
// 契约类会被服务端 Java 代码直接引用,保持零依赖。
android {
    namespace = "io.zer0.muse.vdproto"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
