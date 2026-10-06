# Library consumer rules supplied by Hilt, Retrofit, and kotlinx.serialization remain active.

# ML Kit discovers these Firebase components by names in merged manifest metadata.
# Keep their no-argument constructors so release shrinking does not break discovery.
-keep class com.google.mlkit.common.internal.CommonComponentRegistrar {
    public <init>();
}
-keep class com.google.mlkit.vision.common.internal.VisionCommonRegistrar {
    public <init>();
}
-keep class com.google.mlkit.vision.barcode.internal.BarcodeRegistrar {
    public <init>();
}
