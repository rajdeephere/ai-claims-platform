package com.claimsai.ai.infra;

import com.claimsai.ai.domain.UnreadableDocumentException;
import com.claimsai.support.TestFiles;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentInputPreparerTest {

    private final DocumentInputPreparer preparer = new DocumentInputPreparer();

    @Test
    void aPdfWithTextIsSentAsTextCutToTheBudget() {
        byte[] pdf = TestFiles.pdf("REPAIR ESTIMATE", "City Motors, MG Road", "DATE: 2026-09-22", "TOTAL: 3812.50");

        DocumentInputPreparer.PreparedInput input = preparer.prepare(pdf, "application/pdf", 12000);
        assertThat(input.kind()).isEqualTo(DocumentInputPreparer.Kind.PDF_TEXT);
        assertThat(input.text()).contains("REPAIR ESTIMATE", "TOTAL: 3812.50");
        assertThat(input.imageJpeg()).isNull();

        assertThat(preparer.prepare(pdf, "application/pdf", 1000).text().length()).isLessThanOrEqualTo(1000);
    }

    @Test
    void aScannedPdfWithoutTextIsRenderedForTheVisionModel() throws Exception {
        DocumentInputPreparer.PreparedInput input = preparer.prepare(TestFiles.pdf(), "application/pdf", 12000);

        assertThat(input.kind()).isEqualTo(DocumentInputPreparer.Kind.PDF_PAGE_IMAGE);
        assertThat(ImageIO.read(new ByteArrayInputStream(input.imageJpeg()))).isNotNull();
    }

    @Test
    void largePhotosAreScaledDownAndReEncodedAsJpeg() throws Exception {
        byte[] png = TestFiles.png(4000, 3000);

        DocumentInputPreparer.PreparedInput input = preparer.prepare(png, "image/png", 12000);

        BufferedImage sent = ImageIO.read(new ByteArrayInputStream(input.imageJpeg()));
        assertThat(input.kind()).isEqualTo(DocumentInputPreparer.Kind.PHOTO);
        assertThat(Math.max(sent.getWidth(), sent.getHeight())).isEqualTo(DocumentInputPreparer.MAX_EDGE);
        assertThat(sent.getWidth() * 3).isEqualTo(sent.getHeight() * 4);   // aspect ratio kept
        assertThat(input.imageJpeg()[0]).isEqualTo((byte) 0xFF);           // JPEG magic
    }

    @Test
    void corruptFilesArePermanentFailuresNotRetries() {
        assertThatThrownBy(() -> preparer.prepare("%PDF-1.7 garbage".getBytes(), "application/pdf", 12000))
                .isInstanceOf(UnreadableDocumentException.class);
        assertThatThrownBy(() -> preparer.prepare(new byte[]{1, 2, 3}, "image/jpeg", 12000))
                .isInstanceOf(UnreadableDocumentException.class);
    }
}
