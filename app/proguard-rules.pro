# OkHttp uses reflection against optional platform classes that are absent on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.serialization ships consumer rules; keep serializer lookups for our DTO package
# defensive so R8 full mode never strips the generated companions.
-keepclassmembers class io.github.bjspi.smsrelayer.data.telegram.dto.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.bjspi.smsrelayer.data.telegram.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}
