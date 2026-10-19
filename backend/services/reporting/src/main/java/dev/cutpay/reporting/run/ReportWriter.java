package dev.cutpay.reporting.run;

import dev.cutpay.reporting.run.ReportCatalog.Column;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a report as CSV or as a spreadsheet (.xlsx).
 *
 * <p>An .xlsx file is a zip of a few XML files, so it is written directly here instead of through a library: a workbook with
 * one sheet, text in inline strings and numbers as numbers. Both formats guard against spreadsheet formula injection: a cell
 * that starts with {@code =}, {@code +}, {@code -} (and is not a plain number) or {@code @} is written with a leading
 * apostrophe, so a hostile value cannot run as a formula when the file is opened.
 */
public final class ReportWriter {

    public static final String CSV = "text/csv; charset=utf-8";
    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private ReportWriter() {
    }

    public static byte[] csv(List<Column> columns, List<Map<String, Object>> rows) {
        StringBuilder out = new StringBuilder("﻿"); // the byte order mark lets Excel read the UTF-8 right
        out.append(String.join(",", columns.stream().map(c -> quote(c.label())).toList())).append("\r\n");
        for (Map<String, Object> row : rows) {
            out.append(String.join(",", columns.stream().map(c -> quote(safe(String.valueOf(row.getOrDefault(c.key(), ""))))).toList()))
                    .append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] xlsx(String sheetName, List<Column> columns, List<Map<String, Object>> rows) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    </Types>""");
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>""");
            put(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>""".formatted(xml(sheetName.replaceAll("[\\\\/?*\\[\\]:]", " "))));
            put(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    </Relationships>""");
            StringBuilder sheet = new StringBuilder("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""");
            sheet.append(rowXml(1, columns.stream().map(Column::label).toList()));
            int r = 2;
            for (Map<String, Object> row : rows) {
                sheet.append(rowXml(r++, columns.stream().map(c -> String.valueOf(row.getOrDefault(c.key(), ""))).toList()));
            }
            sheet.append("</sheetData></worksheet>");
            put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String rowXml(int number, List<String> cells) {
        StringBuilder sb = new StringBuilder("<row r=\"").append(number).append("\">");
        for (int i = 0; i < cells.size(); i++) {
            String ref = column(i) + number;
            String value = cells.get(i);
            if (value.matches("-?[0-9]+(\\.[0-9]+)?") && value.length() < 16) {
                sb.append("<c r=\"").append(ref).append("\"><v>").append(value).append("</v></c>");
            } else {
                sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(xml(safe(value))).append("</t></is></c>");
            }
        }
        return sb.append("</row>").toString();
    }

    /** 0 → A, 25 → Z, 26 → AA. */
    static String column(int index) {
        StringBuilder sb = new StringBuilder();
        for (int i = index; i >= 0; i = i / 26 - 1) {
            sb.insert(0, (char) ('A' + i % 26));
        }
        return sb.toString();
    }

    /** A cell that a spreadsheet could take for a formula gets an apostrophe in front. Plain numbers (even negative) are left alone. */
    static String safe(String value) {
        if (value.isEmpty() || value.matches("-?[0-9]+(\\.[0-9]+)?")) {
            return value;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r' ? "'" + value : value;
    }

    private static String quote(String value) {
        return value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
                ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
