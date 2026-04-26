# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

-keep class kotlin.Metadata { *; }

-keep interface com.example.asombrate.OrsApiService { *; }

-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

-keep class com.example.asombrate.GeocodeResponse { *; }
-keep class com.example.asombrate.GeocodeFeature { *; }
-keep class com.example.asombrate.GeocodeGeometry { *; }
-keep class com.example.asombrate.GeocodeProperties { *; }
-keep class com.example.asombrate.GeocodeSearchRequest { *; }
-keep class com.example.asombrate.ReverseGeocodeRequest { *; }
-keep class com.example.asombrate.RouteRequest { *; }
-keep class com.example.asombrate.DirectionsResponse { *; }
-keep class com.example.asombrate.RouteItem { *; }

-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
