package dev.cutpay.billing.invoice;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import dev.cutpay.billing.invoice.InvoiceRepository.Invoice;
import dev.cutpay.billing.invoice.InvoiceRepository.Line;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * The printable invoice, an A4 page. The standard PDF fonts only cover Western European text, so other characters in a
 * name or description print as a question mark (the same limit as the table card of payment links).
 */
@Component
public class InvoicePdf {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color RULE = new Color(0xE5, 0xE7, 0xEB);

    public byte[] render(Invoice invoice) {
        var out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 48, 48, 48, 48);
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            Font title = new Font(Font.HELVETICA, 22, Font.BOLD);
            Font bold = new Font(Font.HELVETICA, 10, Font.BOLD);
            Font text = new Font(Font.HELVETICA, 10);
            Font muted = new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED);

            document.add(new Paragraph("Invoice", title));
            document.add(new Paragraph(invoice.number() == null ? "Draft" : invoice.number(), bold));
            document.add(new Paragraph(" ", text));

            PdfPTable parties = new PdfPTable(2);
            parties.setWidthPercentage(100);
            parties.addCell(plain(new Phrase("From\n", muted), new Phrase(orDash(invoice.merchantName()), bold)));
            parties.addCell(plain(new Phrase("Bill to\n", muted),
                    new Phrase(orDash(invoice.customerName()) + "\n" + orDash(invoice.customerEmail()), text)));
            document.add(parties);
            document.add(new Paragraph(" ", text));

            PdfPTable dates = new PdfPTable(3);
            dates.setWidthPercentage(100);
            dates.addCell(plain(new Phrase("Issued\n", muted), new Phrase(date(invoice.finalizedAt() == null ? invoice.createdAt() : invoice.finalizedAt()), text)));
            dates.addCell(plain(new Phrase("Due\n", muted), new Phrase(date(invoice.dueDate()), text)));
            dates.addCell(plain(new Phrase("Status\n", muted), new Phrase(invoice.status().toUpperCase(Locale.ROOT), bold)));
            document.add(dates);
            document.add(new Paragraph(" ", text));

            PdfPTable table = new PdfPTable(new float[] {5, 1.2f, 1.8f, 1.8f});
            table.setWidthPercentage(100);
            for (String head : new String[] {"Description", "Qty", "Unit price", "Amount"}) {
                PdfPCell cell = new PdfPCell(new Phrase(head, muted));
                cell.setBorder(Rectangle.BOTTOM);
                cell.setBorderColor(RULE);
                cell.setPadding(6);
                if (!head.equals("Description")) {
                    cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                }
                table.addCell(cell);
            }
            for (Line line : invoice.lines()) {
                String description = line.description() + (line.periodStart() != null && line.periodEnd() != null
                        ? "\n" + date(line.periodStart()) + " to " + date(line.periodEnd()) : "");
                table.addCell(row(description, text, Element.ALIGN_LEFT));
                table.addCell(row(String.valueOf(line.quantity()), text, Element.ALIGN_RIGHT));
                table.addCell(row(money(line.unitAmount(), invoice.currency()), text, Element.ALIGN_RIGHT));
                table.addCell(row(money(line.amount(), invoice.currency()), text, Element.ALIGN_RIGHT));
            }
            document.add(table);
            document.add(new Paragraph(" ", text));

            PdfPTable totals = new PdfPTable(2);
            totals.setWidthPercentage(45);
            totals.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totals.addCell(row("Subtotal", text, Element.ALIGN_LEFT));
            totals.addCell(row(money(invoice.subtotal(), invoice.currency()), text, Element.ALIGN_RIGHT));
            if (invoice.tax() > 0) {
                totals.addCell(row("Tax (%s%%)".formatted(new BigDecimal(invoice.taxRateBps()).movePointLeft(2).stripTrailingZeros().toPlainString()),
                        text, Element.ALIGN_LEFT));
                totals.addCell(row(money(invoice.tax(), invoice.currency()), text, Element.ALIGN_RIGHT));
            }
            totals.addCell(row("Total", bold, Element.ALIGN_LEFT));
            totals.addCell(row(money(invoice.total(), invoice.currency()), bold, Element.ALIGN_RIGHT));
            if (invoice.amountPaid() > 0) {
                totals.addCell(row("Paid", text, Element.ALIGN_LEFT));
                totals.addCell(row(money(invoice.amountPaid(), invoice.currency()), text, Element.ALIGN_RIGHT));
            }
            document.add(totals);
            if (invoice.description() != null && !invoice.description().isBlank()) {
                document.add(new Paragraph(" ", text));
                document.add(new Paragraph(invoice.description(), muted));
            }
            document.add(new Paragraph(" ", text));
            document.add(new Paragraph("Educational project: no real money is moved.", muted));
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not draw the invoice", e);
        } finally {
            document.close();
        }
        return out.toByteArray();
    }

    private static PdfPCell plain(Phrase label, Phrase value) {
        Phrase combined = new Phrase();
        combined.add(label);
        combined.add(value);
        PdfPCell cell = new PdfPCell(combined);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingBottom(4);
        return cell;
    }

    private static PdfPCell row(String value, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(value, font));
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(RULE);
        cell.setPadding(6);
        cell.setHorizontalAlignment(alignment);
        return cell;
    }

    static String money(long minor, String currency) {
        return new BigDecimal(minor).movePointLeft(2).setScale(2).toPlainString() + " " + currency;
    }

    private static String date(Instant instant) {
        return instant == null ? "-" : DATE.format(instant);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
