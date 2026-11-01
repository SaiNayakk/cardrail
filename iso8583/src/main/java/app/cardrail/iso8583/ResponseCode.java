package app.cardrail.iso8583;

/** Field 39 values Railnet uses. The codes and meanings are the common ISO 8583:1987 ones. */
public final class ResponseCode {

    public static final String APPROVED = "00";
    public static final String DO_NOT_HONOUR = "05";
    public static final String INVALID_TRANSACTION = "12";
    public static final String INVALID_AMOUNT = "13";
    public static final String INVALID_CARD = "14";
    public static final String FORMAT_ERROR = "30";
    public static final String LOST_CARD = "41";
    public static final String STOLEN_CARD = "43";
    public static final String INSUFFICIENT_FUNDS = "51";
    public static final String EXPIRED_CARD = "54";
    public static final String INCORRECT_PIN = "55";
    public static final String NOT_PERMITTED_TO_CARDHOLDER = "57";
    public static final String EXCEEDS_LIMIT = "61";
    public static final String RESTRICTED_CARD = "62";
    public static final String EXCEEDS_FREQUENCY = "65";
    public static final String NO_ACTION_TAKEN = "21";
    public static final String ORIGINAL_NOT_FOUND = "25";
    public static final String ISSUER_UNAVAILABLE = "91";
    public static final String NO_ROUTE = "92";
    public static final String DUPLICATE = "94";
    public static final String SYSTEM_ERROR = "96";
    public static final String CVV2_MISMATCH = "N7";
    /** Not ISO: Railnet's code for "authenticate the cardholder first" (step-up), as some networks use 1A. */
    public static final String STEP_UP_REQUIRED = "1A";

    private ResponseCode() {
    }

    public static String describe(String code) {
        return switch (code) {
            case APPROVED -> "Approved";
            case DO_NOT_HONOUR -> "Do not honour";
            case INVALID_TRANSACTION -> "Invalid transaction";
            case INVALID_AMOUNT -> "Invalid amount";
            case INVALID_CARD -> "Invalid card number";
            case FORMAT_ERROR -> "Format error";
            case LOST_CARD -> "Lost card";
            case STOLEN_CARD -> "Stolen card";
            case INSUFFICIENT_FUNDS -> "Insufficient funds";
            case EXPIRED_CARD -> "Expired card";
            case INCORRECT_PIN -> "Incorrect PIN";
            case NOT_PERMITTED_TO_CARDHOLDER -> "Not permitted to cardholder";
            case EXCEEDS_LIMIT -> "Exceeds amount limit";
            case RESTRICTED_CARD -> "Restricted card";
            case EXCEEDS_FREQUENCY -> "Exceeds frequency limit";
            case NO_ACTION_TAKEN -> "No action taken";
            case ORIGINAL_NOT_FOUND -> "Original not found";
            case ISSUER_UNAVAILABLE -> "Issuer unavailable";
            case NO_ROUTE -> "No route to issuer";
            case DUPLICATE -> "Duplicate transmission";
            case SYSTEM_ERROR -> "System error";
            case CVV2_MISMATCH -> "Card security code mismatch";
            case STEP_UP_REQUIRED -> "Cardholder authentication required";
            default -> "Code " + code;
        };
    }
}
