# ProGuard / R8 Rules for Soyken Play (Google Play Store Optimized)
# Enables full R8 shrinking + obfuscation so AGP automatically generates mapping.txt
# and embeds BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map in the AAB.

# Preserve crash stack trace line numbers & source file info for Play Console Android Vitals
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Preserve Room database generated implementations, entities, data models, and receivers
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class com.example.data.local.**_Impl { *; }
-keep class com.example.data.model.** { *; }
-keepclassmembers class com.example.data.model.** { *; }
-keep class com.example.receiver.** { *; }
-keep class com.example.billing.FirestorePremiumEntitlement { *; }
-keepclassmembers class com.example.billing.FirestorePremiumEntitlement { *; }
-keep @androidx.annotation.Keep class * { *; }

# Suppress optional transitive platform warnings
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
