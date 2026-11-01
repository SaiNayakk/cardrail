package app.cardrail.network;

/** How the parties are named in the live trace. */
final class Parties {

    static final String RAILNET = "railnet";
    static final String ACQUIRER = "railpay-acquiring";

    private Parties() {
    }

    static String issuer(String institutionId) {
        return "286001".equals(institutionId) ? "kestrel" : "issuer-" + institutionId;
    }

    static String acquirer(String institutionId) {
        return "400101".equals(institutionId) ? ACQUIRER : "acquirer-" + institutionId;
    }
}
