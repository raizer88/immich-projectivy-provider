# AIDL-generated Stub uses reflection-free code, but keep the plugin service and
# provider classes that are referenced from the AndroidManifest (defensive for release builds).
-keep class tv.projectivy.plugin.wallpaperprovider.immich.WallpaperProviderService { *; }
-keep class tv.projectivy.plugin.wallpaperprovider.immich.WallpaperImageProvider { *; }

# The api module ships its own consumer rules (keeps Wallpaper + Event).
