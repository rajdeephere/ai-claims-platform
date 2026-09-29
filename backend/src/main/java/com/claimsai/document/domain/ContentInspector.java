package com.claimsai.document.domain;

import java.io.IOException;
import java.io.InputStream;

/** Reads a file once and tells what it really is: type from its bytes (never its name), size and hash. */
public interface ContentInspector {

    record Inspection(String contentType, long sizeBytes, String sha256) {
    }

    Inspection inspect(InputStream content) throws IOException;
}
