-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keepclassmembers class * {
    @javax.inject.Inject *;
}


-keep class org.apache.lucene.** { *; }
-dontwarn java.lang.Module
-dontwarn java.lang.ModuleLayer
-dontwarn java.lang.Runtime$Version

# -keep class com.bilto.gochmott.models.** { *; }