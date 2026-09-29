package com.claimsai.document.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * What may be uploaded. v1 accepts PDF, JPEG and PNG: what claimants actually send (scans, phone photos,
 * repair estimates) and what the AI pipeline can read. Office files would need parsers that don't fit in
 * 512 MB; users can export to PDF.
 */
public final class FileRules {

    public static final Set<String> ALLOWED_TYPES = Set.of("application/pdf", "image/jpeg", "image/png");
    public static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

    private FileRules() {
    }

    public static boolean isAllowedType(String contentType) {
        return contentType != null && ALLOWED_TYPES.contains(contentType.toLowerCase(Locale.ROOT));
    }

    /**
     * A display name that is safe everywhere it appears: in a Content-Disposition header, in the UI, in
     * logs. Keeps letters, digits, spaces, dot, dash and underscore; drops paths, control characters and
     * quotes. It is never used to build a storage key.
     */
    public static String sanitizeFileName(String name) {
        if (name == null) {
            return "file";
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = Normalizer.normalize(base, Normalizer.Form.NFKC);
        String cleaned = base.replaceAll("[^\\p{L}\\p{N} ._-]", "_").replaceAll("_+", "_").strip();
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);   // no hidden files, no ".." games
        }
        if (cleaned.isBlank()) {
            return "file";
        }
        return cleaned.length() > 150 ? cleaned.substring(cleaned.length() - 150) : cleaned;
    }
}
