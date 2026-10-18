-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
# sherpa-onnx reads Kotlin config fields and calls callbacks from native code.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# The Anthropic SDK (de)serializes its models with Jackson, by reflection on annotated members and on
# the (de)serializer classes named in @JsonDeserialize/@JsonSerialize. Keep exactly those, so R8 can
# still drop the thousands of model classes Folio never touches (the APK must stay under 25 MiB).
-keep class com.anthropic.core.** { *; }
-keepclassmembers class com.anthropic.** {
    @com.fasterxml.jackson.annotation.JsonCreator <init>(...);
    @com.fasterxml.jackson.annotation.JsonCreator *;
    @com.fasterxml.jackson.annotation.JsonProperty *;
    @com.fasterxml.jackson.annotation.JsonAnySetter *;
    @com.fasterxml.jackson.annotation.JsonAnyGetter *;
    @com.fasterxml.jackson.annotation.JsonValue *;
}
-keep @com.fasterxml.jackson.databind.annotation.JsonDeserialize class com.anthropic.** { *; }
-keep @com.fasterxml.jackson.databind.annotation.JsonSerialize class com.anthropic.** { *; }
-keep class com.anthropic.** extends com.fasterxml.jackson.databind.JsonDeserializer { *; }
-keep class com.anthropic.** extends com.fasterxml.jackson.databind.JsonSerializer { *; }
-keep class * extends com.fasterxml.jackson.databind.Module { *; }
-keep class com.fasterxml.jackson.databind.ext.** { *; }
-keep class com.fasterxml.jackson.module.kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn com.anthropic.**
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.**
-dontwarn org.jetbrains.annotations.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
# Optional dependencies of the SDK's JSON-schema and tracing helpers, absent on Android.
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn com.google.auto.value.AutoValue
