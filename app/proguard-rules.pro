# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# ─────────────────────────────────────────
# Guitar Tuner ProGuard Rules
# 中度修正 #3：補全混淆保護規則
# ─────────────────────────────────────────

# Kotlin 反射與 Coroutines 必要保留
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Jetpack Compose - R8 會自動處理大部分，保留 Composable 相關 annotation
-keep @androidx.compose.runtime.Composable class * { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ViewModel - 保留無參數建構子供 ViewModelProvider 使用
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * extends androidx.lifecycle.ViewModelProvider$Factory { *; }

# 保留我們的資料類別（data class 的 copy/equals/toString 可能被混淆掉）
-keep class com.example.guitartuner.Tuning { *; }
-keep class com.example.guitartuner.TunerResult { *; }

# AudioRecord / MediaRecorder 是系統 API，不需保留但避免警告
-dontwarn android.media.**

# 移除 Log.d / Log.v（release 版不需要 debug log）
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}