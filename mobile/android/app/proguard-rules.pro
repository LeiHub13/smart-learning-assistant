# 保留 kotlinx.serialization 生成的serializer与 OkHttp 签名
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.zhixue.assistant.**$$serializer { *; }
-keepclassmembers class com.zhixue.assistant.** { *** Companion; }
-keepclasseswithmembers class com.zhixue.assistant.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn okhttp3.**
-dontwarn okio.**
