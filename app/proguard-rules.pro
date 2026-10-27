# muse ProGuard 规则
# release 已启用 isMinifyEnabled=true + isShrinkResources=true(R8 全量压缩混淆)。
# Phase 11.4: 完善规则集,覆盖全部依赖。补充:Compose / io.zer0.ai / KSP 生成类。

# ============================================================================
# 通用:保留注解、内部类、泛型签名(反射 / 序列化依赖)
# ============================================================================
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations, RuntimeInvisibleParameterAnnotations
-keepattributes RuntimeVisibleTypeAnnotations, RuntimeInvisibleTypeAnnotations

# ============================================================================
# Kotlin:保留元数据(反射用)+ @Metadata 注解
# ============================================================================
-keep class kotlin.Metadata { *; }
-keepclassmembers class ** {
    @kotlin.Metadata *;
}
-dontnote kotlinx.serialization.AnnotationsKt

# ============================================================================
# kotlinx.serialization:序列化器伴生对象 + @Serializable 类
# ============================================================================
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 保留所有 @Serializable 标注的类(项目实体 + 依赖库的 DTO)
-keep,allowobfuscation,allowshrinking @kotlinx.serialization.Serializable class *
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# ============================================================================
# 项目实体类:io.zer0.* 包下的 @Serializable / Room 实体必须保留字段名
# (Room 反射列名 / JSON 序列化字段名 / 备份导入导出兼容性)
# ============================================================================
-keep class io.zer0.muse.data.** { *; }       # SessionEntity/MessageEntity/AssistantEntity 等
-keep class io.zer0.memory.** { *; }          # FactEntity/SessionSummaryEntity 等
-keep class io.zer0.ai.core.** { *; }         # UIMessage/ChatRequest/ChatCompletion 等
-keep class io.zer0.muse.backup.** { *; }     # Backup 数据类
-keep class io.zer0.muse.mcp.** { *; }        # MCP 数据类(McpConfig/McpOAuthConfig 等)
-keep class io.zer0.muse.asr.** { *; }        # AsrConfig
-keep class io.zer0.muse.balance.** { *; }    # BalanceConfig

# ============================================================================
# Room:实体类 + DAO(已通过上面的 io.zer0.muse.data 覆盖,这里加数据库相关)
# ============================================================================
-dontwarn androidx.room.paging.**
-keep class androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }

# ============================================================================
# Koin:模块类 + 反射注入
# ============================================================================
-keep class org.koin.** { *; }
-keep class * extends org.koin.core.module.Module { *; }
-keepclassmembers class * {
    @org.koin.core.annotation.* <methods>;
}

# ============================================================================
# OkHttp + OkHttp SSE:平台相关类 + ConnectionSpec
# ============================================================================
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# M-2: 已注释 — OkHttp 自带 consumer-rules.pro,无需手动 -keep,过度保留会阻止 R8 裁剪死代码
# -keep class okhttp3.** { *; }
# -keep interface okhttp3.** { *; }

# ============================================================================
# Coil:ImageLoader 解码器(SvgDecoder / GifDecoder 反射注册)
# ============================================================================
# M-2: 已注释 — Coil 自带 consumer-rules.pro,无需手动 -keep,过度保留会阻止 R8 裁剪死代码
# -keep class coil.** { *; }
# -keep class coil.decode.** { *; }
# v1.112 (F3): 保留 GIF 动画解码器(ImageDecoderDecoder / GifDecoder)和
# AnimatedImage/AnimatedDrawable 相关类。R8 混淆可能破坏反射注册和 Animatable 生命周期,
# 导致 GIF 只显示第一帧变静态图。
-keep class coil.decode.GifDecoder* { *; }
-keep class coil.decode.ImageDecoderDecoder* { *; }
-keep class coil.decode.AnimationDecoder { *; }
-keep class coil.decode.FrameDecoder { *; }
-keep class coil.image.AnimatedImage { *; }
-keep class coil.drawable.AnimatedDrawable* { *; }
-keep class android.graphics.Movie { *; }
-keep class android.graphics.ImageDecoder { *; }
-keep class android.graphics.ImageDecoder$* { *; }

# ============================================================================
# PDFBox-Android:字体资源 + 反射加载
# ============================================================================
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.**

# ============================================================================
# ML Kit OCR / ML Kit Text Recognition
# ============================================================================
# M-2: 已注释 — ML Kit 自带 consumer-rules.pro,无需手动 -keep,过度保留会阻止 R8 裁剪死代码
# -keep class com.google.mlkit.** { *; }
# -keep class com.google.android.gms.** { *; }
-dontwarn com.google.mlkit.**

# These entry points are instantiated by class name at runtime. Dependency consumer rules keep
# the class declarations but not necessarily their public no-argument constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
-keep class * extends androidx.work.InputMerger {
    public <init>();
}

# ============================================================================
# v1.55: jtokkit(BPE tokenizer)— 保留编码表资源和反射加载的类
# ============================================================================
-keep class com.knuddels.jtokkit.** { *; }
-dontwarn com.knuddels.jtokkit.**

# ============================================================================
# AndroidX Compose:Composable 函数保留(编译器已处理,这里兜底)
# ============================================================================
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }

# ============================================================================
# 反射调用的 Java API(JJWT / S3 签名 / WebDAV 等)
# ============================================================================

# ============================================================================
# 崩溃栈保留:Throwable 子类(便于 CrashHandler 上报)
# ============================================================================
-keep class java.lang.Throwable { *; }
-keep class * extends java.lang.Throwable { *; }

# Shizuku UserService is instantiated by reflection in a non-Android process.
# Keep the class, AIDL Stub methods and constructors across release shrinking.
#
# v2.6.6 修：原来只 keep 了 ShellService 自身，漏了 AIDL 接口与 Stub。R8 把
# IShellService / IShellService$Stub / $Stub$Proxy 当成“仅本进程内使用”剥掉，
# 导致 release 包里 bindUserService 永远等不到 onServiceConnected：
#   授权位 granted（向导显示已授权）→ ensureServiceBound() 超时 →
#   shellEnabled=false → 能力档案告诉模型“Shell 不可用” → 助手说没权限。
# 实测证据：debug 包 classes18.dex 有 IShellService/$Stub/$Stub$Proxy，
# release 包三个 dex 里一个都找不到（dexdump -f 全量搜无命中）。
# UserService 由 Shizuku 在独立进程反射创建并跨进程调用，接口名与
# TRANSACTION 方法必须原样保留，不能改名也不能移除。
-keep class io.zer0.muse.tools.system.IShellService { *; }
-keep class io.zer0.muse.tools.system.IShellService$Stub { *; }
-keep class io.zer0.muse.tools.system.IShellService$Stub$Proxy { *; }
-keep class io.zer0.muse.tools.system.ShellService { *; }

# ============================================================================
# 枚举:values() / valueOf 反射
# ============================================================================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============================================================================
# R8 兼容:避免过度优化导致 ClassCastException
# ============================================================================
-allowaccessmodification
# M-12: 已注释 — -overloadaggressively 有风险,可能导致方法名冲突引发反射调用失败
# -overloadaggressively
-repackageclasses ''

# ============================================================================
# v1.49: Ktor 引用了 JVM 专有类(java.lang.management.*),Android 不提供,
# R8 报 missing class。这些类仅在 IntelliJ 调试检测路径引用,运行时不会触达,
# 用 -dontwarn 抑制即可。
# ============================================================================
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# ============================================================================
# 补充(Phase: release 启用 R8):Compose 全量保留
# 原 androidx.compose.runtime.** 兜底不够,扩展到整个 androidx.compose 包,
# 防止 R8 误删 Composable 函数 / Theme 类 / Modifier 实现。
# ============================================================================
# M-1: 已注释 — Compose 自带 consumer-rules.pro,全量 -keep 会阻止 R8 裁剪死代码,
# 显著增大包体积。上游规则已保留必要的 Composable 函数 / Modifier 实现。
# -keep class androidx.compose.** { *; }
# -keep interface androidx.compose.** { *; }

# ============================================================================
# 补充:io.zer0.ai 全模块 DTO 保留
# 原仅保留 io.zer0.ai.core.**,这里扩展到整个 ai 模块,
# 覆盖 ai 子包内所有 @Serializable 数据类(ChatRequest / ChatCompletion 等)。
# ============================================================================
-keep @kotlinx.serialization.Serializable class *

# ============================================================================
# 补充:KSP 生成的类(Room DAO 实现 / kotlinx.serialization $serializer)
# KSP 生成的类不携带 @Keep 注解,R8 可能误删,显式保留。
#   - **_Impl:Room DAO 实现类(如 SessionDao_Impl)
#   - **_$serializer:kotlinx.serialization 编译器生成的序列化器
#   - Companion / INSTANCE:KSP 生成的伴生对象与单例
# ============================================================================
-keep class **_Impl { *; }
-keep class **_$serializer { *; }
-keepclassmembers class * {
    *** Companion;
    *** INSTANCE;
}

# v2.2.0: Netty(ktor-server-netty, HTTPS 方案 A)引入的可选依赖缺失抑制 —
# 这些类(tcnative OpenSSL 加速/log4j/jetty-npn)在 Android 运行时不会被加载(走 JDK SSL+NIO), 仅 R8 静态检查报警。
-dontwarn io.netty.internal.tcnative.AsyncSSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.AsyncTask
-dontwarn io.netty.internal.tcnative.Buffer
-dontwarn io.netty.internal.tcnative.CertificateCallback
-dontwarn io.netty.internal.tcnative.CertificateCompressionAlgo
-dontwarn io.netty.internal.tcnative.CertificateVerifier
-dontwarn io.netty.internal.tcnative.Library
-dontwarn io.netty.internal.tcnative.SSL
-dontwarn io.netty.internal.tcnative.SSLContext
-dontwarn io.netty.internal.tcnative.SSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.SSLSessionCache
-dontwarn io.netty.internal.tcnative.SessionTicketKey
-dontwarn io.netty.internal.tcnative.SniHostNameMatcher
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.apache.logging.log4j.message.MessageFactory
-dontwarn org.apache.logging.log4j.spi.ExtendedLogger
-dontwarn org.apache.logging.log4j.spi.ExtendedLoggerWrapper
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ClientProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$Provider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ServerProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego

# v2.6.3 (issue #4): KTOR-7298 —— Ktor 3.0.x 在 R8 混淆的 release 构建里,
# engine factory 的 ServiceLoader 探测会拿到多个候选,内部 single() 抛
# `IllegalArgumentException: Array has more than one element`,WebServer 永远起不来。
# 虽然已升级到 Ktor 3.1.3(官方修复),这里仍 keep 住 ServiceLoader 约定与 engine
# 实现类,避免未来依赖变更再次触发同类问题。
-keep class io.ktor.server.engine.ApplicationEngineFactory { *; }
-keep class * implements io.ktor.server.engine.ApplicationEngineFactory { *; }
-keep class io.ktor.server.engine.EngineMain { *; }
-keep class io.ktor.server.netty.NettyApplicationEngine { *; }
-keep class io.ktor.server.netty.NettyApplicationEngine$Configuration { *; }
-keepnames class io.ktor.server.engine.ApplicationEngineFactory
-keepnames class * implements io.ktor.server.engine.ApplicationEngineFactory
-keepattributes *Annotation*
-keep class io.ktor.server.config.** { *; }
-keep class io.ktor.util.logging.** { *; }
-keepclassmembers class * extends io.ktor.server.engine.BaseApplicationEngine { *; }


# ============================================================================
# R8 优化关闭
#
# MessageBubble 是单个 2000+ 行、70+ 参数的 Composable，局部寄存器用量超过
# 200 个槽。R8 的优化（方法内联 / 寄存器重排 / 类合并）在这个体量上会产出
# 类型不一致的 dex，被 Android 14+ 的 ART verifier 拒收，表现为点开对话直接
# 抛 VerifyError: copy-cat1 ... type=Reference: MutableState。
#
# 关闭优化后仍然保留混淆（类名/成员名改写）与资源压缩，源码保护不受影响，
# 代价是包体增大。根治方案是把这方法体拆小，拆分完成即可移除本开关。
-dontoptimize
