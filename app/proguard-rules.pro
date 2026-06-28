# Keep JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep JavascriptInterface methods (WebView bridges)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep AIDL interfaces
-keep class com.mineradio.app.IRemoteInjector { *; }
-keep class com.mineradio.app.IRemoteInjector$Stub { *; }
-keep class com.mineradio.app.IRemoteInjector$Stub$Proxy { *; }

# Keep RemoteInjectorService (Shizuku user service)
-keep class com.mineradio.app.service.RemoteInjectorService { *; }

# Keep Shizuku
-keep class rikka.shizuku.** { *; }

# Keep TFLite/ONNX runtime
-keep class org.tensorflow.** { *; }
-keep class ai.onnxruntime.** { *; }
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options

# Keep data classes used in JSON serialization
-keepclassmembers class com.mineradio.app.manager.AppConfig { *; }
-keepclassmembers class com.mineradio.app.model.AreaConfig { *; }

# Keep MediaSession & MediaStyle (通知栏/锁屏媒体控件)
-keep class android.support.v4.media.** { *; }
-keep class androidx.media.** { *; }
-dontwarn android.support.v4.media.**

# Remove log calls in release build
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Optimize
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''
