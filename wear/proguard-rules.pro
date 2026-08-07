# :core 用 kotlinx-serialization，生成的 $$serializer 必须留下，
# 否则 spec 解析会在运行时抛 SerializationException。
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class dev.liji.mihome.core.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.liji.mihome.core.**$$serializer { *; }
-keep class dev.liji.mihome.core.**$Companion { *; }

# Tile 由系统进程按类名反射拉起
-keep class dev.liji.mihome.MiTileService { *; }
-keep class dev.liji.mihome.ToggleActivity { *; }
