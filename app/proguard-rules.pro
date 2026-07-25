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
-keep class java.lang.reflect.** { *; }
-keep class java.security.** { *; }

# ============================================================================
# 崩溃栈保留:Throwable 子类(便于 CrashHandler 上报)
# ============================================================================
-keep class java.lang.Throwable { *; }
-keep class * extends java.lang.Throwable { *; }

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
-keep class io.zer0.ai.** { *; }

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
