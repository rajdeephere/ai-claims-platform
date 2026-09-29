package com.claimsai.ai.infra;

import com.claimsai.ai.domain.UnreadableDocumentException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Turns a verified file into what the model can read (ADR-0021):
 * <ul>
 *   <li>PDF with a text layer: its text (first 5 pages), cut to the token budget: cheap and exact</li>
 *   <li>PDF without text (a scan): page 1 rendered at 110 dpi for the vision model</li>
 *   <li>JPEG/PNG: scaled so the long edge is at most 1568 px and re-encoded as JPEG (smaller request,
 *       and it also strips metadata such as GPS coordinates before anything leaves our system)</li>
 * </ul>
 */
@Component
public class DocumentInputPreparer {

    static final int MAX_EDGE = 1568;
    private static final int MIN_TEXT_CHARS = 40;

    public enum Kind { PDF_TEXT, PDF_PAGE_IMAGE, PHOTO }

    public record PreparedInput(Kind kind, String text, byte[] imageJpeg) {
    }

    public PreparedInput prepare(byte[] content, String contentType, int maxChars) {
        if ("application/pdf".equals(contentType)) {
            return pdf(content, maxChars);
        }
        return new PreparedInput(Kind.PHOTO, null, jpeg(decode(content)));
    }

    private PreparedInput pdf(byte[] content, int maxChars) {
        try (PDDocument pdf = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setEndPage(5);
            String text = stripper.getText(pdf).strip();
            if (text.length() >= MIN_TEXT_CHARS) {
                return new PreparedInput(Kind.PDF_TEXT, text.length() > maxChars ? text.substring(0, maxChars) : text, null);
            }
            if (pdf.getNumberOfPages() == 0) {
                throw new UnreadableDocumentException("PDF has no pages", null);
            }
            BufferedImage page = new PDFRenderer(pdf).renderImageWithDPI(0, 110, ImageType.RGB);
            return new PreparedInput(Kind.PDF_PAGE_IMAGE, null, jpeg(page));
        } catch (IOException e) {
            throw new UnreadableDocumentException("PDF can't be read: " + e.getMessage(), e);
        }
    }

    private static BufferedImage decode(byte[] content) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            if (image == null) {
                throw new UnreadableDocumentException("image can't be decoded", null);
            }
            return image;
        } catch (IOException e) {
            throw new UnreadableDocumentException("image can't be decoded: " + e.getMessage(), e);
        }
    }

    /** Scale down (never up), flatten transparency onto white, JPEG quality 0.85. */
    static byte[] jpeg(BufferedImage source) {
        double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } catch (IOException e) {
            throw new UnreadableDocumentException("image can't be encoded: " + e.getMessage(), e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
