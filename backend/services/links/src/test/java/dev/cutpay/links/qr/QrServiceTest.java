package dev.cutpay.links.qr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import dev.cutpay.links.qr.QrService.Colors;
import dev.cutpay.web.ApiException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class QrServiceTest {

    private static final String URL = "http://localhost:5174/l/AbCdEfGhJk";
    private final QrService qr = new QrService();
    private final Colors black = Colors.from(Map.of(), null, null);

    @Test
    void thePngScansBackToTheSameAddress() throws Exception {
        byte[] png = qr.png(URL, black, 512);
        var image = ImageIO.read(new ByteArrayInputStream(png));
        var bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        assertThat(new MultiFormatReader().decode(bitmap).getText()).isEqualTo(URL);
        assertThat(image.getWidth()).isEqualTo(image.getHeight()).isLessThanOrEqualTo(512);
    }

    @Test
    void aColouredPngStillScans() throws Exception {
        byte[] png = qr.png(URL, Colors.from(Map.of("fg", "#0a3d62", "bg", "#f1f8ff"), null, null), 400);
        var image = ImageIO.read(new ByteArrayInputStream(png));
        var bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        assertThat(new MultiFormatReader().decode(bitmap).getText()).isEqualTo(URL);
    }

    @Test
    void theSvgIsAStandaloneImageInTheRequestedColours() {
        String svg = qr.svg(URL, Colors.from(Map.of("fg", "#0a3d62"), null, "#ffeedd"));
        assertThat(svg).startsWith("<svg ").contains("fill=\"#0a3d62\"").contains("fill=\"#ffeedd\"").endsWith("</svg>");
        assertThat(svg).doesNotContain("<script");
    }

    @Test
    void queryColoursOverrideTheSavedStyleButGarbageIsIgnored() {
        Colors c = Colors.from(Map.of("fg", "#112233"), "#445566", "red;");
        assertThat(c.fg()).isEqualTo("#445566");
        assertThat(c.bg()).isEqualTo("#ffffff");
    }

    @Test
    void theTableCardIsAOnePagePdf() {
        byte[] pdf = qr.tableCard("Table 4 – Café България", "Rose Cafe", URL, black);
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        assertThat(text).startsWith("%PDF-");
        assertThat(text).contains("/Count 1");
    }

    @Test
    void emptyOrHugeContentIsRefused() {
        assertThatThrownBy(() -> qr.svg(" ", black)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> qr.svg("x".repeat(1001), black)).isInstanceOf(ApiException.class);
    }

    @Test
    void sizesAreClamped() {
        assertThat(QrService.clampSize(null)).isEqualTo(512);
        assertThat(QrService.clampSize(1)).isEqualTo(128);
        assertThat(QrService.clampSize(100_000)).isEqualTo(2048);
    }
}
