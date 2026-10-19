package dev.cutpay.reporting.run;

import dev.cutpay.events.OutboxWriter;
import dev.cutpay.reporting.config.ReportingProperties;
import dev.cutpay.reporting.config.Topics;
import dev.cutpay.reporting.run.ReportCatalog.Column;
import dev.cutpay.reporting.run.ReportCatalog.ReportType;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Report runs: asked for in a request, built by a job, kept for download. Asking is quick and building is not (a large
 * period can mean many rows), so the request returns at once with a run that is {@code queued}.
 */
@Service
public class ReportRunService {

    private static final Logger log = LoggerFactory.getLogger(ReportRunService.class);
    static final long MAX_DAYS = 366;

    public record Run(String id, String type, String format, LocalDate intervalStart, LocalDate intervalEnd, List<String> columns,
                      String status, String error, Integer rowCount, Instant createdAt, Instant completedAt) {
    }

    private final JdbcClient jdbc;
    private final ReportCatalog catalog;
    private final OutboxWriter outbox;
    private final ReportingProperties properties;

    public ReportRunService(JdbcClient jdbc, ReportCatalog catalog, OutboxWriter outbox, ReportingProperties properties) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.outbox = outbox;
        this.properties = properties;
    }

    @Transactional
    public Run create(String merchantId, boolean livemode, String requestedByEmail, String type, String format, String start,
                      String end, List<String> columns) {
        ReportType reportType = catalog.type(type).orElseThrow(() -> ApiException.invalidRequest("unknown_report_type",
                "Unknown report type. See /v1/reporting/report_types.", "type"));
        String fmt = format == null ? "csv" : format;
        if (!fmt.equals("csv") && !fmt.equals("xlsx")) {
            throw ApiException.invalidRequest("invalid_format", "Format must be csv or xlsx", "format");
        }
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(start);
            to = LocalDate.parse(end);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.invalidRequest("invalid_interval", "Give interval_start and interval_end as year-month-day", "interval_start");
        }
        if (to.isBefore(from)) {
            throw ApiException.invalidRequest("invalid_interval", "The end of the period is before its start", "interval_end");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw ApiException.invalidRequest("interval_too_long", "A report covers at most " + MAX_DAYS + " days", "interval_end");
        }
        String chosen = null;
        if (columns != null && !columns.isEmpty()) {
            List<String> known = reportType.columns().stream().map(Column::key).toList();
            for (String column : columns) {
                if (!known.contains(column)) {
                    throw ApiException.invalidRequest("unknown_column", "This report has no column '" + column + "'", "columns");
                }
            }
            chosen = String.join(",", columns);
        }
        String id = Ids.generate("rpt");
        jdbc.sql("""
                        INSERT INTO report_runs (id, merchant_id, livemode, type, format, interval_start, interval_end, columns, status,
                                                 requested_by_email)
                        VALUES (:id, :m, :l, :type, :format, :from, :to, :columns, 'queued', :email)
                        """)
                .param("id", id).param("m", merchantId).param("l", livemode).param("type", type).param("format", fmt)
                .param("from", java.sql.Date.valueOf(from)).param("to", java.sql.Date.valueOf(to))
                .param("columns", chosen, java.sql.Types.VARCHAR).param("email", requestedByEmail, java.sql.Types.VARCHAR).update();
        return find(merchantId, livemode, id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<Run> find(String merchantId, boolean livemode, String id) {
        return jdbc.sql(SELECT + " WHERE id = :id AND merchant_id = :m AND livemode = :l")
                .param("id", id).param("m", merchantId).param("l", livemode).query(this::map).optional();
    }

    @Transactional(readOnly = true)
    public List<Run> list(String merchantId, boolean livemode, int limit) {
        return jdbc.sql(SELECT + " WHERE merchant_id = :m AND livemode = :l ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("m", merchantId).param("l", livemode).param("limit", limit).query(this::map).list();
    }

    public record File(String filename, String contentType, byte[] content) {
    }

    @Transactional(readOnly = true)
    public Optional<File> file(String runId) {
        return jdbc.sql("SELECT filename, content_type, content FROM report_files WHERE run_id = :id").param("id", runId)
                .query((rs, n) -> new File(rs.getString(1), rs.getString(2), rs.getBytes(3))).optional();
    }

    /** Builds the oldest queued run. Returns false when there was none. */
    @Transactional
    public boolean buildNext() {
        record Queued(String id, String merchantId, boolean livemode, String type, String format, LocalDate start, LocalDate end,
                      String columns, String email) {
        }
        Optional<Queued> next = jdbc.sql("""
                        SELECT id, merchant_id, livemode, type, format, interval_start, interval_end, columns, requested_by_email
                        FROM report_runs WHERE status = 'queued' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                        """)
                .query((rs, n) -> new Queued(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4), rs.getString(5),
                        rs.getDate(6).toLocalDate(), rs.getDate(7).toLocalDate(), rs.getString(8), rs.getString(9)))
                .optional();
        if (next.isEmpty()) {
            return false;
        }
        Queued q = next.get();
        try {
            ReportType type = catalog.type(q.type()).orElseThrow();
            List<Column> columns = q.columns() == null ? type.columns()
                    : Arrays.stream(q.columns().split(",")).map(key -> type.columns().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow()).toList();
            var rows = catalog.run(q.type(), q.merchantId(), q.livemode(), q.start(), q.end());
            byte[] content = q.format().equals("xlsx") ? ReportWriter.xlsx(type.title(), columns, rows) : ReportWriter.csv(columns, rows);
            String name = "%s_%s_%s.%s".formatted(q.type(), q.start(), q.end(), q.format());
            jdbc.sql("INSERT INTO report_files (run_id, filename, content_type, content) VALUES (:id, :name, :type, :content)")
                    .param("id", q.id()).param("name", name)
                    .param("type", q.format().equals("xlsx") ? ReportWriter.XLSX : ReportWriter.CSV).param("content", content).update();
            jdbc.sql("UPDATE report_runs SET status = 'succeeded', row_count = :n, completed_at = :now WHERE id = :id")
                    .param("n", rows.size()).param("now", Timestamp.from(Instant.now())).param("id", q.id()).update();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("report_run_id", q.id());
            payload.put("report_type", type.title());
            payload.put("requested_by_email", q.email());
            // The email points at the page, where a fresh signed link is made; a link in an inbox would be stale by the time it is read.
            payload.put("download_url", properties.dashboardUrl() + "/reports");
            outbox.write(Topics.REPORT, "ReportRunSucceeded", q.id(), q.merchantId(), q.livemode(), payload);
        } catch (RuntimeException e) {
            log.warn("Report {} failed", q.id(), e);
            jdbc.sql("UPDATE report_runs SET status = 'failed', error = :error, completed_at = :now WHERE id = :id")
                    .param("error", String.valueOf(e.getMessage())).param("now", Timestamp.from(Instant.now())).param("id", q.id()).update();
        }
        return true;
    }

    private static final String SELECT = """
            SELECT id, type, format, interval_start, interval_end, columns, status, error, row_count, created_at, completed_at
            FROM report_runs""";

    private Run map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Run(rs.getString("id"), rs.getString("type"), rs.getString("format"), rs.getDate("interval_start").toLocalDate(),
                rs.getDate("interval_end").toLocalDate(),
                rs.getString("columns") == null ? null : List.of(rs.getString("columns").split(",")), rs.getString("status"),
                rs.getString("error"), (Integer) rs.getObject("row_count"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
    }
}
