package com.claimsai.document.domain;

/** Outbox event published when a document's bytes are verified; phase 5 starts the AI assessment from it. */
public final class DocumentEvents {

    private DocumentEvents() {
    }

    public static final String AGGREGATE = "DOCUMENT";
    public static final String DOCUMENT_UPLOADED = "DOCUMENT_UPLOADED";

    public static final String DOCUMENT_ID = "documentId";
    public static final String CLAIM_ID = "claimId";
}
