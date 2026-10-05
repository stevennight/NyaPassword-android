# UniFFI bindings call libnpw_android.so through JNA, which finds classes and
# methods by name and reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class app.nya.password.ffi.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# kotlinx.serialization: generated serializers of the model classes.
-keepclassmembers @kotlinx.serialization.Serializable class app.nya.password.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
