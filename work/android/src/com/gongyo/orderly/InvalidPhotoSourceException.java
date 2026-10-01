package com.gongyo.orderly;
import java.io.IOException;
import java.util.Objects;
/** Positive incoming source rejection; owned media and opaque I/O remain storage/transport errors. */
public final class InvalidPhotoSourceException extends IOException {
 public enum Reason { MALFORMED, UNSUPPORTED, LIMIT_EXCEEDED }
 public final Reason reason;
 public InvalidPhotoSourceException(Reason reason,String message){super(message);this.reason=Objects.requireNonNull(reason,"reason");}
 public InvalidPhotoSourceException(Reason reason,String message,Throwable cause){super(message,cause);this.reason=Objects.requireNonNull(reason,"reason");}
}
