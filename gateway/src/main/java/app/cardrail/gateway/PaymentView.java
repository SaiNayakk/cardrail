package app.cardrail.gateway;

import java.time.Instant;
import java.util.List;

/** A payment as merchants see it, in the API and in webhooks. Card numbers never appear: only brand and last four. */
public record PaymentView(String id, String object, long amount, String currency, String status, CardView card, String reference,
                          String authCode, String responseCode, String declineReason, String description, Boolean capture,
                          Long amountCaptured, Long amountRefunded, List<RefundView> refunds, NextAction nextAction, Instant createdAt) {

    /** What the merchant's page must do next: show the shopper their bank's page to confirm the payment (v3). */
    public record NextAction(String type, String url) {}

    public record CardView(String brand, String last4) {}

    public record RefundView(String id, String object, long amount, String status, String reference, String failureReason,
                             String payment, Instant createdAt) {

        static RefundView of(Payments.Refund r) {
            return new RefundView(r.id(), "refund", r.amountCents(), r.status().toLowerCase(), r.rrn(), r.failureReason(),
                    r.paymentId(), r.createdAt());
        }
    }

    /** Version 2: a payment waiting on step-up (made through v3) shows as pending, a status v2 knows. */
    static PaymentView of(Payments.Payment p, List<Payments.Refund> refunds) {
        String status = "REQUIRES_ACTION".equals(p.status()) ? "pending" : p.status().toLowerCase();
        return new PaymentView(p.id(), "payment", p.amountCents(), p.currency().toLowerCase(), status,
                new CardView(p.cardBrand(), p.cardLast4()), p.rrn(), p.authCode(), p.responseCode(), p.declineReason(),
                p.description(), p.capture(), p.capturedCents(), p.refundedCents(),
                refunds.stream().map(RefundView::of).toList(), null, p.createdAt());
    }

    /** Version 3: as version 2, with "requires_action" and where to send the shopper. */
    static PaymentView v3(Payments.Payment p, List<Payments.Refund> refunds, String challengePath) {
        PaymentView v2 = of(p, refunds);
        boolean waiting = "REQUIRES_ACTION".equals(p.status());
        return new PaymentView(v2.id(), v2.object(), v2.amount(), v2.currency(), waiting ? "requires_action" : v2.status(), v2.card(),
                v2.reference(), v2.authCode(), v2.responseCode(), v2.declineReason(), v2.description(), v2.capture(), v2.amountCaptured(),
                v2.amountRefunded(), v2.refunds(), waiting && challengePath != null ? new NextAction("authenticate", challengePath) : null,
                v2.createdAt());
    }
}
