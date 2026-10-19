package dev.cutpay.reporting.run;

import dev.cutpay.reporting.run.ReportCatalog.ReportType;
import dev.cutpay.reporting.run.ReportRunService.File;
import dev.cutpay.reporting.run.ReportRunService.Run;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.RequestMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.LocalDate;
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

@RestController
class ReportController {

    record CreateRunRequest(@NotBlank String type, String format, @NotBlank String intervalStart, @NotBlank String intervalEnd,
                            List<String> columns) {
    }

    record RunView(String object, String id, String type, String format, LocalDate intervalStart, LocalDate intervalEnd,
                   List<String> columns, String status, String error, Integer rowCount, Instant created, Instant completedAt,
                   String downloadUrl, Long downloadExpiresIn) {
    }

    record RunList(String object, List<RunView> data) {
    }

    record TypeList(String object, List<ReportType> data) {
    }

    private final ReportRunService runs;
    private final ReportCatalog catalog;
    private final SignedLinks links;

    ReportController(ReportRunService runs, ReportCatalog catalog, SignedLinks links) {
        this.runs = runs;
        this.catalog = catalog;
        this.links = links;
    }

    private static boolean livemode(Jwt jwt, boolean requested) {
        return RequestMode.resolve(jwt.getClaimAsBoolean("livemode"), requested);
    }

    /** A finished run gets a fresh signed link each time it is read. */
    private RunView view(Run r) {
        var link = "succeeded".equals(r.status()) ? links.create(r.id()) : null;
        return new RunView("report_run", r.id(), r.type(), r.format(), r.intervalStart(), r.intervalEnd(), r.columns(), r.status(),
                r.error(), r.rowCount(), r.createdAt(), r.completedAt(), link == null ? null : link.path(),
                link == null ? null : link.expiresIn());
    }

    @GetMapping("/v1/reporting/report_types")
    TypeList types() {
        return new TypeList("list", catalog.types());
    }

    @PostMapping("/v1/reporting/report_runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    RunView create(@AuthenticationPrincipal Jwt jwt, @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                   @Valid @RequestBody CreateRunRequest request) {
        return view(runs.create(jwt.getClaimAsString("mid"), livemode(jwt, requestedLivemode), jwt.getClaimAsString("email"),
                request.type(), request.format(), request.intervalStart(), request.intervalEnd(), request.columns()));
    }

    @GetMapping("/v1/reporting/report_runs")
    RunList list(@AuthenticationPrincipal Jwt jwt, @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                 @RequestParam(defaultValue = "20") int limit) {
        return new RunList("list", runs.list(jwt.getClaimAsString("mid"), livemode(jwt, requestedLivemode), Math.max(1, Math.min(limit, 100)))
                .stream().map(this::view).toList());
    }

    @GetMapping("/v1/reporting/report_runs/{id}")
    RunView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode) {
        return view(runs.find(jwt.getClaimAsString("mid"), livemode(jwt, requestedLivemode), id)
                .orElseThrow(() -> ApiException.notFound("report_run_not_found", "No such report run")));
    }

    /** No token: the signed, expiring link is the permission. */
    @GetMapping("/v1/reporting/files/{id}")
    ResponseEntity<byte[]> download(@PathVariable String id, @RequestParam long expires, @RequestParam String signature) {
        if (!links.valid(id, expires, signature)) {
            throw ApiException.forbidden("invalid_link", "This download link is invalid or has expired. Open the report again for a new one.");
        }
        File file = runs.file(id).orElseThrow(() -> ApiException.notFound("report_not_found", "No such report"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
