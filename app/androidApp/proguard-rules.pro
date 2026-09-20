#######################################################################
# secp256k1-kmp JNI (Elliptic Curve Cryptography)
# Loader is the only entry point needing protection - R8 traces the rest
#######################################################################

-keep,allowoptimization class fr.acinq.secp256k1.jni.NativeSecp256k1AndroidLoader {
    public static fr.acinq.secp256k1.Secp256k1 load();
}

#######################################################################
# libsodium / kotlin-multiplatform-libsodium (uses JNA, not JNI)
# JNA uses reflection to map interface methods to native functions
# nostr-kmp uses: sha256, random, chacha20-ietf, memcmp
#######################################################################

-keep class com.ionspin.kotlin.crypto.JnaLibsodiumInterface { *; }
-keep class com.ionspin.kotlin.crypto.Hash256State { *; }

# JNA core classes - accessed via JNI reflection internally
-keep class com.sun.jna.* { *; }
-keep class com.sun.jna.ptr.* { *; }
-dontwarn com.sun.jna.**

#######################################################################
# Strip ALL Android Log calls everywhere (app + all deps)
#######################################################################
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}

#######################################################################
# Strip nwc-kmp’s logging facade (quiet the library at call sites)
# Safe even if the lib changes its backend; affects only NwcLog calls.
#######################################################################
# -assumenosideeffects class io.github.nostr.nwc.logging.NwcLog {
#    public *** trace(...);
#    public *** debug(...);
#    public *** info(...);
#    public *** warn(...);
#    public *** error(...);
#    public *** log(...);
#}
# FIXME: The above does not work because logs are inline...fix logging upstream
# until then we do this, should be fine but nukes everything

# Strip ALL stdout/stderr printing in release
-assumenosideeffects class java.io.PrintStream {
  public *** print(...);
  public *** println(...);
  public *** printf(...);
  public *** format(...);
  public *** append(...);
  public *** write(...);
  public void flush();
}
