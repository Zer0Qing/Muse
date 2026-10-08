plugins {
    alias(libs.plugins.android.application)
}

// v2.2.1 虚拟屏:shell uid 独立进程服务端。
//
// 产物是 APK,但从不安装 —— 改名 jar 后由主应用写到 /data/local/tmp,
// 用 `CLASSPATH=<jar> app_process / io.zer0.muse.vdserver.Main <hostPkg>` 拉起。
//
// 全 Java + 零第三方依赖:保证 classes.dex 单文件(带 kotlin-stdlib 会变多 dex,
// app_process 的 CLASSPATH 只能可靠加载第一段 dex)。
android {
    namespace = "io.zer0.muse.vdserver"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.zer0.muse.vdserver"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // app_process 的 CLASSPATH 只能可靠加载 classes.dex 一段,强制单 dex
        multiDexEnabled = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // v2.3.2: 本模块的存在意义就是"补 framework"(见文件头注释)—— app_process 进程没有经过
        // zygote 的常规初始化,只能靠反射把 ActivityThread / DisplayManager 等私有状态补进去。
        // 因此按模块豁免这两条"私有 API"检查;豁免范围仅限这两条,其余 lint 规则照常生效,
        // 这样将来真正误用私有 API 的**新增**代码仍会被其他规则拦住。
        // (逐处 @SuppressLint 的写法会打地鼠:当前已有 5 个文件在做同类反射。)
        disable += setOf("BlockedPrivateApi", "SoonBlockedPrivateApi")
    }
}

dependencies {
    implementation(project(":virtual-display-protocol"))
}
