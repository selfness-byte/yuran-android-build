package com.gongyo.orderly;

import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;

/** Pure-Java boundary for temporary generated PDF and XLSX files. */
public final class ShareFileAccess {
    private static final Pattern NAME = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(pdf|xlsx)");

    private ShareFileAccess() {}

    public static File resolve(File root, String name, String mode) throws IOException {
        if (!"r".equals(mode) || root == null || name == null || !NAME.matcher(name).matches()) {
            throw new IOException("Share file access denied");
        }
        File canonicalRoot = root.getCanonicalFile();
        File file = new File(canonicalRoot, name).getCanonicalFile();
        if (!file.getParentFile().equals(canonicalRoot) || !file.isFile()) {
            throw new IOException("Share file unavailable");
        }
        return file;
    }

    public static String mimeType(String name) throws IOException {
        if (name == null || !NAME.matcher(name).matches()) throw new IOException("Unsupported share file");
        return name.endsWith(".pdf") ? "application/pdf" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }
}
