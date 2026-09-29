package com.claimsai.document.infra;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class S3DocumentStorageTest {

    @Test
    void downloadsAreAttachmentsWithAnAsciiFallbackAndTheUtf8Name() {
        assertThat(S3DocumentStorage.contentDisposition("Schaden Übersicht.pdf"))
                .isEqualTo("attachment; filename=\"Schaden _bersicht.pdf\"; filename*=UTF-8''Schaden%20%C3%9Cbersicht.pdf");
    }
}
