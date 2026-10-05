-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Haze / Compose 无需额外规则；保留 SQLite 相关反射边界
-keep class android.database.** { *; }
-keep class android.database.sqlite.** { *; }
