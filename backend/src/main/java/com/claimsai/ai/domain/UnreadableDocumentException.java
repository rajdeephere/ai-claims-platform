package com.claimsai.ai.domain;

/** The file can't be turned into model input (corrupt PDF, undecodable image). Permanent: retrying won't help. */
public class UnreadableDocumentException extends RuntimeException {

    public UnreadableDocumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
