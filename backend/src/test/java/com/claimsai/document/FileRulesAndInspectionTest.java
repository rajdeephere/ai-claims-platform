package com.claimsai.document;

import com.claimsai.common.util.Hashing;
import com.claimsai.document.domain.ContentInspector.Inspection;
import com.claimsai.document.domain.FileRules;
import com.claimsai.document.infra.TikaContentInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class FileRulesAndInspectionTest {

    public static final byte[] PDF = "%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R',
            0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0};
    public static final byte[] EXE = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0, (byte) 0xFF, (byte) 0xFF};

    private final TikaContentInspector inspector = new TikaContentInspector();

    private Inspection inspect(byte[] bytes) throws Exception {
        return inspector.inspect(new ByteArrayInputStream(bytes));
    }

    @Test
    void theTypeComesFromTheBytesAndTheHashCoversTheWholeFile() throws Exception {
        Inspection pdf = inspect(PDF);

        assertThat(pdf.contentType()).isEqualTo("application/pdf");
        assertThat(pdf.sizeBytes()).isEqualTo(PDF.length);
        assertThat(pdf.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PDF)));
        assertThat(inspect(PNG).contentType()).isEqualTo("image/png");
    }

    @Test
    void aProgramIsRecognisedWhateverItIsCalled() throws Exception {
        String type = inspect(EXE).contentType();

        assertThat(type).isNotIn(FileRules.ALLOWED_TYPES);
        assertThat(FileRules.isAllowedType(type)).isFalse();
    }

    @Test
    void largeFilesAreHashedCompletelyNotJustTheBufferedStart() throws Exception {
        byte[] big = new byte[3 * 1024 * 1024];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        big[big.length - 1] = 42;   // a change at the very end must change the hash

        Inspection inspection = inspect(big);

        assertThat(inspection.sizeBytes()).isEqualTo(big.length);
        assertThat(inspection.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(big)));
    }

    @Test
    void onlyPdfJpegAndPngAreAllowed() {
        assertThat(FileRules.isAllowedType("application/pdf")).isTrue();
        assertThat(FileRules.isAllowedType("IMAGE/JPEG")).isTrue();
        assertThat(FileRules.isAllowedType("text/html")).isFalse();
        assertThat(FileRules.isAllowedType(null)).isFalse();
        assertThat(Hashing.sha256Hex("x")).hasSize(64);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "repair estimate.pdf            | repair estimate.pdf",
            "../../etc/passwd               | passwd",
            "C:\\\\Users\\\\me\\\\photo.jpg | photo.jpg",
            "..hidden.png                   | hidden.png",
            "invoice\"; x=\"y.pdf            | invoice_ x_y.pdf",
            "photo<script>.png              | photo_script_.png",
            "Schaden-Übersicht.pdf          | Schaden-Übersicht.pdf",
            "'   '                           | file"
    })
    void fileNamesAreMadeSafeForHeadersLogsAndTheUi(String input, String expected) {
        assertThat(FileRules.sanitizeFileName(input)).isEqualTo(expected);
    }

    @Test
    void veryLongNamesKeepTheirExtension() {
        String name = FileRules.sanitizeFileName("a".repeat(300) + ".pdf");

        assertThat(name).hasSize(150).endsWith(".pdf");
    }
}
