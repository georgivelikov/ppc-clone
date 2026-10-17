package dev.cutpay.links.qr;

import dev.cutpay.links.config.LinkProperties;
import dev.cutpay.links.link.Link;
import dev.cutpay.links.link.LinkService;
import dev.cutpay.links.qr.QrService.Colors;
import dev.cutpay.web.ApiException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class QrController {

    private static final MediaType SVG = MediaType.parseMediaType("image/svg+xml");

    private final LinkService links;
    private final QrService qr;
    private final LinkProperties properties;

    QrController(LinkService links, QrService qr, LinkProperties properties) {
        this.links = links;
        this.qr = qr;
        this.properties = properties;
    }

    /** The link's QR code. {@code fg} and {@code bg} (as #rrggbb) override the saved colours, for live previews. */
    @GetMapping("/v1/payment_links/{id}/qr")
    ResponseEntity<byte[]> linkQr(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                  @RequestParam(defaultValue = "svg") String format,
                                  @RequestParam(required = false) Integer size,
                                  @RequestParam(required = false) String fg,
                                  @RequestParam(required = false) String bg) {
        Link link = links.owned(jwt.getClaimAsString("mid"), jwt.getClaimAsBoolean("livemode"), id);
        return render(url(link), format, size, Colors.from(link.qrStyle(), fg, bg));
    }

    /** Print-ready A6 table card with the link's QR code. */
    @GetMapping("/v1/payment_links/{id}/card.pdf")
    ResponseEntity<byte[]> card(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        Link link = links.owned(jwt.getClaimAsString("mid"), jwt.getClaimAsBoolean("livemode"), id);
        byte[] pdf = qr.tableCard(link.title(), link.merchantName(), url(link), Colors.from(link.qrStyle(), null, null));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + link.slug() + "-card.pdf\"")
                .body(pdf);
    }

    /**
     * A QR code for one checkout address, used by "Charge with QR". Limited to our own checkout, so the endpoint
     * cannot be used as a free QR generator for arbitrary links.
     */
    @GetMapping("/v1/qr")
    ResponseEntity<byte[]> qrFor(@RequestParam String data, @RequestParam(defaultValue = "svg") String format,
                                 @RequestParam(required = false) Integer size) {
        if (!data.startsWith(properties.checkoutUrl() + "/")) {
            throw ApiException.invalidRequest("invalid_qr_data", "Only checkout addresses can be encoded", "data");
        }
        return render(data, format, size, Colors.from(java.util.Map.of(), null, null));
    }

    private ResponseEntity<byte[]> render(String content, String format, Integer size, Colors colors) {
        var response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        return switch (format) {
            case "svg" -> response.contentType(SVG).body(qr.svg(content, colors).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            case "png" -> response.contentType(MediaType.IMAGE_PNG).body(qr.png(content, colors, QrService.clampSize(size)));
            default -> throw ApiException.invalidRequest("invalid_format", "Use svg or png", "format");
        };
    }

    private String url(Link link) {
        return properties.checkoutUrl() + "/l/" + link.slug();
    }
}
