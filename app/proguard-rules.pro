-keepattributes Signature, InnerClasses, EnclosingMethod
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class net.berkchy.zerochan.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class net.berkchy.zerochan.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class net.berkchy.zerochan.core.model.** { *; }
-dontwarn org.jsoup.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**