package com.gongyo.orderly;
import java.io.File;
/** A caller-owned staged upright JPEG. The hash covers these exact encoded bytes. */
public final class PreparedPhoto {
 public final File file;public final String hash;public final long byteLength;public final int width,height;
 public PreparedPhoto(File file,String hash,long byteLength,int width,int height){this.file=file;this.hash=hash;this.byteLength=byteLength;this.width=width;this.height=height;}
}
