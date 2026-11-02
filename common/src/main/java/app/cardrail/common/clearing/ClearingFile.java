package app.cardrail.common.clearing;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Railnet's clearing file: fixed-width ASCII records of 160 characters, one per line, the way card networks' batch
 * files are (Visa's BASE II and Mastercard's IPM are binary or longer, but the shape is the same): a header, the
 * transactions, and a trailer with a count and a total so a truncated file is noticed.
 *
 * <pre>
 * Header   H0 | file id 20 | sender 11 | receiver 11 | business date 8 | created 14 | spaces
 * Detail   type 2 | acquirer 11 | issuer 11 | RRN 12 | auth code 6 | amount 12 | currency 3 | date 8 | MCC 4
 *          | merchant id 15 | terminal 8 | BIN 6 | last four 4 | interchange 12 | reason 4 | original RRN 12 | dispute 30
 * Trailer  T0 | record count 8 | total amount 15 | spaces
 * </pre>
 *
 * Records carry the BIN and last four, never a full card number: every party finds the authorisation a record
 * belongs to by the acquirer and the RRN. Interchange is filled in by the network.
 */
public final class ClearingFile {

    public static final int WIDTH = 160;
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** Transaction codes, after Visa's. */
    public enum Type {
        /** First presentment: the merchant takes the money. */
        SALE("05"),
        /** A refund: money back to the cardholder. */
        CREDIT("06"),
        /** The issuer disputes a sale on the cardholder's behalf: the money goes back. */
        CHARGEBACK("15"),
        /** The acquirer answers a chargeback with evidence: the money comes back to the merchant. */
        REPRESENTMENT("25");

        public final String code;

        Type(String code) {
            this.code = code;
        }

        static Type of(String code) {
            for (Type t : values()) {
                if (t.code.equals(code)) {
                    return t;
                }
            }
            throw new IllegalArgumentException("unknown record type " + code);
        }
    }

    public record Header(String fileId, String sender, String receiver, LocalDate businessDate, LocalDateTime created) {}

    public record Record(Type type, String acquirerId, String issuerId, String rrn, String authCode, long amountCents,
                         String currency, LocalDate date, String mcc, String merchantId, String terminalId, String bin,
                         String last4, long interchangeCents, String reasonCode, String originalRrn, String disputeId) {

        public Record withIssuer(String issuer) {
            return new Record(type, acquirerId, issuer, rrn, authCode, amountCents, currency, date, mcc, merchantId, terminalId,
                    bin, last4, interchangeCents, reasonCode, originalRrn, disputeId);
        }

        public Record withInterchange(long cents) {
            return new Record(type, acquirerId, issuerId, rrn, authCode, amountCents, currency, date, mcc, merchantId, terminalId,
                    bin, last4, cents, reasonCode, originalRrn, disputeId);
        }
    }

    public record Parsed(Header header, List<Record> records) {}

    private ClearingFile() {
    }

    public static String write(Header header, List<Record> records) {
        StringBuilder out = new StringBuilder();
        line(out, "H0" + text(header.fileId(), 20) + num(header.sender(), 11) + num(header.receiver(), 11)
                + header.businessDate().format(DATE) + header.created().format(STAMP));
        long total = 0;
        for (Record r : records) {
            line(out, r.type().code + num(r.acquirerId(), 11) + num(r.issuerId(), 11) + text(r.rrn(), 12) + text(r.authCode(), 6)
                    + num(r.amountCents(), 12) + num(r.currency(), 3) + r.date().format(DATE) + num(r.mcc(), 4)
                    + text(r.merchantId(), 15) + text(r.terminalId(), 8) + num(r.bin(), 6) + num(r.last4(), 4)
                    + num(r.interchangeCents(), 12) + text(r.reasonCode(), 4) + text(r.originalRrn(), 12) + text(r.disputeId(), 30));
            total += r.amountCents();
        }
        line(out, "T0" + num(records.size(), 8) + num(total, 15));
        return out.toString();
    }

    public static Parsed parse(String file) {
        String[] lines = file.split("\n", -1);
        int n = lines.length;
        if (n > 0 && lines[n - 1].isEmpty()) {
            n--;
        }
        if (n < 2) {
            throw new ClearingException("a clearing file has at least a header and a trailer");
        }
        for (int i = 0; i < n; i++) {
            if (lines[i].length() != WIDTH) {
                throw new ClearingException("line " + (i + 1) + " is " + lines[i].length() + " characters, not " + WIDTH);
            }
        }
        String h = lines[0];
        if (!h.startsWith("H0")) {
            throw new ClearingException("the first line isn't a header");
        }
        Header header = new Header(h.substring(2, 22).trim(), strip(h.substring(22, 33)), strip(h.substring(33, 44)),
                LocalDate.parse(h.substring(44, 52), DATE), LocalDateTime.parse(h.substring(52, 66), STAMP));
        String t = lines[n - 1];
        if (!t.startsWith("T0")) {
            throw new ClearingException("the last line isn't a trailer");
        }
        List<Record> records = new ArrayList<>();
        long total = 0;
        for (int i = 1; i < n - 1; i++) {
            String d = lines[i];
            try {
                Record r = new Record(Type.of(d.substring(0, 2)), strip(d.substring(2, 13)), strip(d.substring(13, 24)),
                        d.substring(24, 36).trim(), blank(d.substring(36, 42)), Long.parseLong(d.substring(42, 54)),
                        d.substring(54, 57), LocalDate.parse(d.substring(57, 65), DATE), d.substring(65, 69),
                        d.substring(69, 84).trim(), d.substring(84, 92).trim(), d.substring(92, 98), d.substring(98, 102),
                        Long.parseLong(d.substring(102, 114)), blank(d.substring(114, 118)), blank(d.substring(118, 130)),
                        blank(d.substring(130, 160)));
                records.add(r);
                total += r.amountCents();
            } catch (RuntimeException e) {
                throw new ClearingException("line " + (i + 1) + " can't be read: " + e.getMessage());
            }
        }
        long count = Long.parseLong(t.substring(2, 10));
        long declared = Long.parseLong(t.substring(10, 25));
        if (count != records.size() || declared != total) {
            throw new ClearingException("the trailer says " + count + " records for " + declared + " cents; the file has "
                    + records.size() + " for " + total);
        }
        return new Parsed(header, records);
    }

    private static void line(StringBuilder out, String content) {
        if (content.length() > WIDTH) {
            throw new ClearingException("a record is longer than " + WIDTH + " characters");
        }
        out.append(content).append(" ".repeat(WIDTH - content.length())).append('\n');
    }

    private static String num(long value, int width) {
        if (value < 0) {
            throw new ClearingException("amounts in a clearing file are never negative");
        }
        String s = Long.toString(value);
        if (s.length() > width) {
            throw new ClearingException(value + " doesn't fit in " + width + " digits");
        }
        return "0".repeat(width - s.length()) + s;
    }

    private static String num(String value, int width) {
        if (value == null || !value.matches("[0-9]*") || value.length() > width) {
            throw new ClearingException("'" + value + "' isn't up to " + width + " digits");
        }
        return "0".repeat(width - value.length()) + value;
    }

    private static String text(String value, int width) {
        String v = value == null ? "" : value;
        if (v.length() > width || !v.chars().allMatch(c -> c >= 0x20 && c <= 0x7E)) {
            throw new ClearingException("'" + v + "' doesn't fit in " + width + " printable characters");
        }
        return v + " ".repeat(width - v.length());
    }

    /** Institution ids are numbers, zero-padded on the left. */
    private static String strip(String padded) {
        String s = padded.replaceFirst("^0+", "");
        return s.isEmpty() ? "0" : s;
    }

    private static String blank(String s) {
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public static class ClearingException extends RuntimeException {
        public ClearingException(String message) {
            super(message);
        }
    }
}
