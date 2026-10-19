package dev.cutpay.payments.dispute;

import dev.cutpay.payments.dispute.DisputeService.Dispute;
import dev.cutpay.payments.dispute.DisputeService.FileContent;
import dev.cutpay.payments.dispute.DisputeService.FileInfo;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.RequestMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
class DisputeController {

    record FileView(String object, String id, String filename, String contentType, int size, Instant uploadedAt) {

        static FileView of(FileInfo f) {
            return new FileView("dispute_file", f.id(), f.filename(), f.contentType(), f.size(), f.uploadedAt());
        }
    }

    record DisputeView(String object, String id, String paymentIntent, long amount, String currency, long fee, String reason,
                       String status, Instant evidenceDueBy, Instant evidenceSubmittedAt, String evidenceText,
                       String outcomeReason, Instant created, Instant closedAt, List<FileView> files, boolean livemode) {

        static DisputeView of(Dispute d) {
            return new DisputeView("dispute", d.id(), d.paymentId(), d.amount(), d.currency(), d.fee(), d.reason(),
                    d.status(), d.evidenceDueBy(), d.evidenceSubmittedAt(), d.evidenceText(), d.outcomeReason(),
                    d.openedAt(), d.closedAt(), d.files().stream().map(FileView::of).toList(), d.livemode());
        }
    }

    record DisputeList(String object, List<DisputeView> data, boolean hasMore) {
    }

    record EvidenceRequest(@Size(max = 5000) String evidence) {
    }

    private final DisputeService disputes;

    DisputeController(DisputeService disputes) {
        this.disputes = disputes;
    }

    private static boolean livemode(Jwt jwt, boolean requested) {
        return RequestMode.resolve(jwt.getClaimAsBoolean("livemode"), requested);
    }

    @GetMapping("/v1/disputes")
    DisputeList list(@AuthenticationPrincipal Jwt jwt,
                     @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                     @RequestParam(required = false) String status,
                     @RequestParam(name = "payment_intent", required = false) String paymentIntent,
                     @RequestParam(name = "starting_after", required = false) String startingAfter,
                     @RequestParam(defaultValue = "20") int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        var found = disputes.list(jwt.getClaimAsString("mid"), livemode(jwt, requestedLivemode), status, paymentIntent,
                startingAfter, size + 1);
        return new DisputeList("list", found.stream().limit(size).map(DisputeView::of).toList(), found.size() > size);
    }

    @GetMapping("/v1/disputes/{id}")
    DisputeView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                    @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode) {
        return DisputeView.of(disputes.find(jwt.getClaimAsString("mid"), livemode(jwt, requestedLivemode), id)
                .orElseThrow(() -> ApiException.notFound("dispute_not_found", "No such dispute")));
    }

    @PostMapping("/v1/disputes/{id}/evidence")
    DisputeView evidence(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                         @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                         @Valid @RequestBody EvidenceRequest request) {
        return DisputeView.of(disputes.submitEvidence(jwt.getClaimAsString("mid"), livemode(jwt, requestedLivemode), id,
                request.evidence()));
    }

    @PostMapping(value = "/v1/disputes/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    FileView upload(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                    @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                    @RequestParam("file") MultipartFile file) throws IOException {
        return FileView.of(disputes.addFile(jwt.getClaimAsString("mid"), livemode(jwt, requestedLivemode), id,
                file.getOriginalFilename(), file.getContentType(), file.getBytes()));
    }

    @GetMapping("/v1/disputes/{id}/files/{fileId}")
    ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String fileId,
                                    @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode) {
        FileContent file = disputes.file(jwt.getClaimAsString("mid"), livemode(jwt, requestedLivemode), id, fileId)
                .orElseThrow(() -> ApiException.notFound("file_not_found", "No such file"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM) // never served as what the uploader claims it is
                .body(file.content());
    }

    @PostMapping("/v1/disputes/{id}/accept")
    DisputeView accept(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                       @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode) {
        return DisputeView.of(disputes.accept(jwt.getClaimAsString("mid"), livemode(jwt, requestedLivemode), id));
    }
}
