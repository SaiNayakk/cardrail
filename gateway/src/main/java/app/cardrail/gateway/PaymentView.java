package app.cardrail.gateway;

import java.time.Instant;
import java.util.List;

/** A payment as merchants see it, in the API and in webhooks. Card numbers never appear: only brand and last four. */
public record PaymentView(String id, String object, long amount, String currency, String status, CardView card, String reference,
                          String authCode, String responseCode, String declineReason, String description, Boolean capture,
                          Long amountCaptured, Long amountRefunded, List<RefundView> refunds, Instant createdAt) {

    public record CardView(String brand, String last4) {}

    public record RefundView(String id, String object, long amount, String status, String reference, String failureReason,
                             String payment, Instant createdAt) {

        static RefundView of(Payments.Refund r) {
            return new RefundView(r.id(), "refund", r.amountCents(), r.status().toLowerCase(), r.rrn(), r.failureReason(),
                    r.paymentId(), r.createdAt());
        }
    }

    static PaymentView of(Payments.Payment p, List<Payments.Refund> refunds) {
        return new PaymentView(p.id(), "payment", p.amountCents(), p.currency().toLowerCase(), p.status().toLowerCase(),
                new CardView(p.cardBrand(), p.cardLast4()), p.rrn(), p.authCode(), p.responseCode(), p.declineReason(),
                p.description(), p.capture(), p.capturedCents(), p.refundedCents(),
                refunds.stream().map(RefundView::of).toList(), p.createdAt());
    }
}
