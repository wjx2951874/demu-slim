# 保留 miuix 组件：内部有反射/序列化，整体保下来最稳
-keep class top.yukonga.miuix.kmp.** { *; }
-keep interface top.yukonga.miuix.kmp.** { *; }

# 自己的代码（体积很小，保下来避免任何反射问题）
-keep class com.demu.slim.** { *; }

# Compose 运行时
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keep class androidx.compose.runtime.** { *; }

# kotlin 协程
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { *; }

# KMP 里可能残留的桌面端引用
-dontwarn org.jetbrains.skia.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn org.jetbrains.skiko.**

# 保留注解与签名信息
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
