# MeshNet ProGuard/R8 Rules
# ==========================

# Keep MeshNet native mesh engine classes
-keep class com.meshnet.meshnet_app.** { *; }
-keep class com.meshnet.meshnet_app.protocol.** { *; }
-keep class com.meshnet.meshnet_app.localnet.** { *; }
-keep class com.meshnet.meshnet_app.transport.** { *; }
-keep class com.meshnet.meshnet_app.crypto.** { *; }
-keep class com.meshnet.meshnet_app.storage.** { *; }

# Keep Flutter/Dart bridge classes
-keep class io.flutter.** { *; }
-keep class io.flutter.plugin.** { *; }
-keep class io.flutter.embedding.** { *; }

# Keep BouncyCastle crypto classes
-keep class org.bouncycastle.** { *; }

# Keep Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }

# Keep Gson for JSON serialization
-keep class com.google.gson.** { *; }
-keepattributes *Annotation*

# Keep MeshNet Protocol classes
-keep class com.meshnet.meshnet_app.protocol.MeshFrame { *; }
-keep class com.meshnet.meshnet_app.protocol.MessageType { *; }
-keep class com.meshnet.meshnet_app.protocol.RoutingEngine { *; }
-keep class com.meshnet.meshnet_app.protocol.FileTransferManager { *; }

# Keep LocalNet classes
-keep class com.meshnet.meshnet_app.localnet.LocalNetService { *; }
-keep class com.meshnet.meshnet_app.localnet.DnsRegistry { *; }
-keep class com.meshnet.meshnet_app.localnet.LocalHttpServer { *; }
-keep class com.meshnet.meshnet_app.localnet.chunk.** { *; }
-keep class com.meshnet.meshnet_app.localnet.collab.** { *; }
-keep class com.meshnet.meshnet_app.localnet.vpn.** { *; }
-keep class com.meshnet.meshnet_app.localnet.apps.** { *; }
-keep class com.meshnet.meshnet_app.localnet.emergency.** { *; }
-keep class com.meshnet.meshnet_app.localnet.search.** { *; }
-keep class com.meshnet.meshnet_app.localnet.rbac.** { *; }

# Keep Crypto classes
-keep class com.meshnet.meshnet_app.crypto.MeshCrypto { *; }
-keep class com.meshnet.meshnet_app.crypto.MeshCrypto$* { *; }

# Keep storage classes
-keep class com.meshnet.meshnet_app.storage.** { *; }

# Keep Flutter channel classes
-keep class io.flutter.plugin.common.** { *; }

# Prevent obfuscation of JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Serializable/Parcelable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    !private <fields>;
    !private <methods>;
}

-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# Keep enum fields
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep mesh engine entry points
-keep class com.meshnet.meshnet_app.MeshEngine { *; }
-keep class com.meshnet.meshnet_app.IdentityStore { *; }
-keep class com.meshnet.meshnet_app.MeshService { *; }

# Preserve annotations
-keepattributes *Annotation*, EnclosingMethod, InnerClasses, Signature

# Don't optimize crypto operations (timing attacks)
-keep class com.meshnet.meshnet_app.crypto.** {
    public <methods>;
}

# Keep mesh frame serialization
-keep class com.meshnet.meshnet_app.protocol.MeshFrame {
    <init>(...);
    <fields>;
    <methods>;
}

# Prevent removal of JNI bridge
-keep class com.meshnet.meshnet_app.crypto.MeshCrypto {
    native <methods>;
}

# Keep Google Play services (for Flutter deferred components)
-keep class com.google.android.play.core.** { *; }
-keep class com.google.android.play.core.splitcompat.** { *; }
-keep class com.google.android.play.core.splitinstall.** { *; }
-keep class com.google.android.play.core.tasks.** { *; }
-keep class com.google.android.play.core.splitcompat.SplitCompatApplication { *; }

# Suppress warnings for missing optional classes
-dontwarn com.google.android.play.core.splitcompat.SplitCompatApplication
-dontwarn com.google.android.play.core.splitcompat.**
-dontwarn com.google.android.play.core.splitinstall.**
-dontwarn com.google.android.play.core.tasks.**

# Keep JNDI classes (for BouncyCastle LDAP)
-dontwarn javax.naming.**
-dontwarn javax.naming.directory.**
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.NamingEnumeration

# Keep BouncyCastle LDAP classes
-keep class org.bouncycastle.jce.provider.X509LDAPCertStoreSpi { *; }
-keep class org.bouncycastle.jce.provider.CrlCache { *; }

# Ignore missing classes from optional dependencies
-dontwarn com.google.android.play.core.splitinstall.**
-dontwarn com.google.android.play.core.tasks.**
-dontwarn com.google.android.play.core.tasks.Task
-dontwarn com.google.android.play.core.tasks.OnSuccessListener
-dontwarn com.google.android.play.core.tasks.OnFailureListener

# Optimize
-optimizationpasses 5
-allowaccessmodification
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers