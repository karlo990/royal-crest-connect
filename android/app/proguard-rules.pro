# Gson reads the API models by reflection: keep their fields' names.
-keep class zw.royalcrest.connect.data.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
# Retrofit
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn javax.annotation.**
# Shrink only, don't rename: Retrofit and Gson work by reflection, and this
# APK can't be tried on a phone before it ships, so take no chances.
-dontobfuscate
# Gson
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * { @com.google.gson.annotations.SerializedName <fields>; }
