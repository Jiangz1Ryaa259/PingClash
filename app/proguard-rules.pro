# Gomobile resolves these names and fields across the JNI boundary.
-keep class go.** { *; }
-keep class dev.pingclash.mobile.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
# Termux's native PTY bridge uses fixed Java class names.
-keep class com.termux.terminal.JNI { *; }
# Bounded fault-injection regressions inspect these fields on the minified APK.
# Keeping their names adds no exported component, listener or runtime test hook.
-keepclassmembers class dev.pingclash.app.ProfileRepository {
    kotlinx.coroutines.flow.MutableStateFlow mutableData;
}
-keepclassmembers class dev.pingclash.app.PingVpnService {
    android.os.ParcelFileDescriptor descriptor;
}
