# Add project specific ProGuard rules here.
# By default, the flags in this file are applied to all build variants.
# https://www.guardsquare.com/manual/configuration/usage

# Hilt
-keepattributes *Annotation*
-keepclassmembers class * {
    @javax.inject.Inject <fields>;
    @javax.inject.Inject <init>(...);
}
