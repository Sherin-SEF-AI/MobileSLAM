# MapPilot release rules. Keep domain models used reflectively by serialization.
-keep class com.mappilot.core.model.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
