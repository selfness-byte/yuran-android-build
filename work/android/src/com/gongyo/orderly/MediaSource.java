package com.gongyo.orderly;
import java.io.IOException;
import java.io.InputStream;
/** A pinned export may read only its frozen, confirmed photo versions. */
public interface MediaSource { InputStream open(String photoVersionId) throws IOException;
 default void checkReady() throws IOException {} }
