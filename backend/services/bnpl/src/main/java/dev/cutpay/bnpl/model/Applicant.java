package dev.cutpay.bnpl.model;

import java.util.ArrayList;
import java.util.List;

/**
 * What the customer tells the provider about themselves. Which of these a provider needs differs, see
 * {@code BnplProviderAdapter.requiredFields}. {@code monthlyIncome} is in minor units.
 */
public record Applicant(String email, String name, String birthdate, String nationalId, Long monthlyIncome) {

    public static final String EMAIL = "email";
    public static final String BIRTHDATE = "birthdate";
    public static final String NATIONAL_ID = "national_id";
    public static final String MONTHLY_INCOME = "monthly_income";

    /** The required fields this applicant has not filled in. */
    public List<String> missing(List<String> required) {
        List<String> missing = new ArrayList<>();
        for (String field : required) {
            boolean present = switch (field) {
                case EMAIL -> email != null && email.contains("@");
                case BIRTHDATE -> birthdate != null && !birthdate.isBlank();
                case NATIONAL_ID -> nationalId != null && !nationalId.isBlank();
                case MONTHLY_INCOME -> monthlyIncome != null && monthlyIncome > 0;
                default -> true;
            };
            if (!present) {
                missing.add(field);
            }
        }
        return missing;
    }
}
