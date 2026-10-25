# Firebase ComponentDiscovery reflects over ComponentRegistrar implementations
# declared in AndroidManifest metadata. Its bundled consumer rule keeps those
# classes but not their zero-argument constructors, which R8 can remove.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}
