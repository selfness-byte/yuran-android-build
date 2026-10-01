package com.gongyo.orderly;
import java.io.IOException;
/** Publication may have happened, and durable restoration could not be established. */
public final class CommitUncertainException extends IOException {
 public CommitUncertainException(String message,IOException primary,IOException recovery){super(message,primary);if(recovery!=null)addSuppressed(recovery);}
}
