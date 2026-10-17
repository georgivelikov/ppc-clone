package dev.cutpay.links.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import dev.cutpay.web.ApiException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

/**
 * Draws QR codes. ZXing only works out which squares are dark; the picture is ours so the colours are
 * the merchant's and the SVG is a handful of rectangles that stay sharp at any print size.
 */
@Service
public class QrService {

    static final int MIN_SIZE = 128;
    static final int MAX_SIZE = 2048;
    static final int DEFAULT_SIZE = 512;
    /** The standard asks for four modules of empty border so scanners can find the code. */
    private static final int QUIET_ZONE = 4;
    private static final Charset LATIN1 = Charset.forName("windows-1252");

    public record Colors(String fg, String bg) {
        public static Colors from(Map<String, String> style, String fg, String bg) {
            return new Colors(pick(fg, style.get("fg"), "#000000"), pick(bg, style.get("bg"), "#ffffff"));
        }

        private static String pick(String override, String stored, String fallback) {
            for (String candidate : new String[] {override, stored}) {
                if (candidate != null && candidate.matches("#[0-9a-fA-F]{6}")) {
                    return candidate.toLowerCase(java.util.Locale.ROOT);
                }
            }
            return fallback;
        }
    }

    public static int clampSize(Integer size) {
        return size == null ? DEFAULT_SIZE : Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
    }

    /** The dark squares, without the border. */
    BitMatrix matrix(String content) {
        if (content == null || content.isBlank() || content.length() > 1000) {
            throw ApiException.invalidRequest("invalid_qr_data", "Nothing to encode, or too long", "data");
        }
        try {
            return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                            EncodeHintType.CHARACTER_SET, "UTF-8", EncodeHintType.MARGIN, 0));
        } catch (WriterException e) {
            throw ApiException.invalidRequest("invalid_qr_data", "This text cannot be encoded", "data");
        }
    }

    public String svg(String content, Colors colors) {
        BitMatrix m = matrix(content);
        int side = m.getWidth() + 2 * QUIET_ZONE;
        var out = new StringBuilder();
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(side).append(' ').append(side)
                .append("\" shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"QR code\">")
                .append("<rect width=\"").append(side).append("\" height=\"").append(side).append("\" fill=\"")
                .append(colors.bg()).append("\"/><path fill=\"").append(colors.fg()).append("\" d=\"");
        for (int y = 0; y < m.getHeight(); y++) {
            int x = 0;
            while (x < m.getWidth()) {
                if (!m.get(x, y)) {
                    x++;
                    continue;
                }
                int start = x;
                while (x < m.getWidth() && m.get(x, y)) {
                    x++;
                }
                // One rectangle per horizontal run of dark modules keeps the file small.
                out.append('M').append(start + QUIET_ZONE).append(' ').append(y + QUIET_ZONE).append('h')
                        .append(x - start).append("v1h-").append(x - start).append('z');
            }
        }
        return out.append("\"/></svg>").toString();
    }

    public byte[] png(String content, Colors colors, int size) {
        BitMatrix m = matrix(content);
        int modules = m.getWidth() + 2 * QUIET_ZONE;
        // Whole pixels per module, so edges are never blurred; the image is the largest multiple that fits.
        int scale = Math.max(1, size / modules);
        int px = modules * scale;
        BufferedImage image = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        int fg = Color.decode(colors.fg()).getRGB();
        int bg = Color.decode(colors.bg()).getRGB();
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                int mx = x / scale - QUIET_ZONE;
                int my = y / scale - QUIET_ZONE;
                boolean dark = mx >= 0 && my >= 0 && mx < m.getWidth() && my < m.getHeight() && m.get(mx, my);
                image.setRGB(x, y, dark ? fg : bg);
            }
        }
        var bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", bytes);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * A6 table card (105 x 148 mm): the title, a big code and the address in words for people who cannot scan.
     * The standard PDF fonts only cover Western European text, so other characters print as {@code ?}.
     */
    public byte[] tableCard(String title, String merchantName, String url, Colors colors) {
        var out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A6, 24, 24, 28, 24);
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            Font heading = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
            Font small = FontFactory.getFont(FontFactory.HELVETICA, 9, new java.awt.Color(0x55, 0x55, 0x55));
            Font urlFont = FontFactory.getFont(FontFactory.COURIER, 8);
            if (merchantName != null && !merchantName.isBlank()) {
                Paragraph merchant = new Paragraph(latin(merchantName), small);
                merchant.setAlignment(Element.ALIGN_CENTER);
                document.add(merchant);
            }
            Paragraph name = new Paragraph(latin(title), heading);
            name.setAlignment(Element.ALIGN_CENTER);
            name.setSpacingAfter(10);
            document.add(name);
            Image qr = Image.getInstance(png(url, colors, 900));
            qr.scaleToFit(190, 190);
            qr.setAlignment(Element.ALIGN_CENTER);
            document.add(qr);
            Paragraph scan = new Paragraph("Scan to pay", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14));
            scan.setAlignment(Element.ALIGN_CENTER);
            scan.setSpacingBefore(10);
            document.add(scan);
            Paragraph address = new Paragraph(url, urlFont);
            address.setAlignment(Element.ALIGN_CENTER);
            address.setSpacingBefore(6);
            document.add(address);
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Could not build the table card", e);
        } finally {
            document.close();
        }
        return out.toByteArray();
    }

    private static String latin(String text) {
        return new String(text.getBytes(LATIN1), LATIN1).codePoints()
                .map(cp -> cp < 0x20 ? ' ' : cp)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }
}
