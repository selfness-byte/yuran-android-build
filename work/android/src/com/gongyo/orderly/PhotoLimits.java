package com.gongyo.orderly;

/** Shared bounds for the owner metadata, media, protocol and backup adapters. */
public final class PhotoLimits {
    private PhotoLimits() {}
    public static final int SCHEMA_VERSION=11;
    public static final int MAX_BARCODES_PER_VARIANT=8, MAX_BARCODE_CHARS=128;
    public static final int MAX_LEGACY_BYTES=33554432, MAX_METADATA_BYTES=67108864;
    public static final int MAX_PRODUCTS=5000, MAX_VARIANTS=20000, MAX_PHOTO_ASSETS=20000;
    public static final int MAX_CONFIRMED_ALIASES=40000, MAX_RECEIPTS=50000;
    public static final int MAX_MANIFEST_BYTES=8388608, MAX_MEDIA=20000, MAX_ZIP_FILES=20002;
    public static final int MAX_INPUT_BYTES=26214400, MAX_INPUT_PIXELS=40000000;
    public static final int MAX_REGISTERED_EDGE=1600, MAX_THUMBNAIL_EDGE=192, JPEG_QUALITY=82;
    public static final int MAX_SKU_CHARS=80, MAX_SOURCE_COLOR_CHARS=60, MAX_BUNDLE_BYTES=65536;
    public static final int MAX_PAGE_SIZE=50;
}
