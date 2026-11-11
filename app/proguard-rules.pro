# NewPipeExtractor relies on reflection (Rhino JS engine for YouTube signature deobfuscation)
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn javax.annotation.**
-dontwarn org.jspecify.**
-dontwarn com.google.errorprone.annotations.**

# Some transitive deps reference desktop-only JDK classes
-ignorewarnings

# Keep line numbers for readable crash logs
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
