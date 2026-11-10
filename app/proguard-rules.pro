# Keep Room entities and DAOs
-keep class androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep Hilt and Dagger generated code
-keep class * extends com.meshchat.app.MeshChatApp
-keep class * implements io.grpc.InternalMayRequireSpecificExecutor
-dontwarn com.google.errorprone.annotations.**

# Google Play Services Nearby Connections
-keep class com.google.android.gms.nearby.** { *; }

# Keep Data Models & Serializable protocol packets
-keep class com.meshchat.app.data.model.** { *; }
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Android Keystore & Security Crypto
-keep class androidx.security.crypto.** { *; }
