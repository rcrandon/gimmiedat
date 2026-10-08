# youtubedl-android: its updater and mappers lean on Jackson, which reflects over its own classes.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-dontwarn com.fasterxml.jackson.**
-dontwarn java.beans.**
-dontwarn org.w3c.dom.bootstrap.DOMImplementationRegistry

# The engine (python, ffmpeg) is unzipped on first run with commons-compress, which
# instantiates its zip extra-field handlers reflectively. Renaming them breaks the unpack.
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-keep class org.apache.commons.io.** { *; }
-dontwarn org.apache.commons.io.**

# keep exception names readable in the rare crash report
-keepnames class * extends java.lang.Throwable
