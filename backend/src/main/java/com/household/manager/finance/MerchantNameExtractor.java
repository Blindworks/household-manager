package com.household.manager.finance;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts the real merchant name from the purpose text of an intermediary booking. */
@Component
public class MerchantNameExtractor {

    private static final Pattern PAYPAL_MERCHANT = Pattern.compile("(?i)Ihr Einkauf bei\\s+([^,\\r\\n]+)");

    private static final Pattern TRAILING_LEGAL_FORM = Pattern.compile(
            "(?i)\\s+(?:GmbH\\s*&\\s*Co\\.\\s*KG|GmbH|AG|SE|KG|OHG|GbR|UG\\s*\\(haftungsbeschränkt\\)|UG"
                    + "|e\\.K\\.|Ltd|Inc|S\\.a\\.r\\.l\\.|S\\.A\\.|B\\.V\\.)\\.?\\s*$");

    public Optional<String> extract(PaymentIntermediary intermediary, String purpose) {
        if (intermediary != PaymentIntermediary.PAYPAL || purpose == null) {
            return Optional.empty();
        }
        Matcher m = PAYPAL_MERCHANT.matcher(purpose);
        if (!m.find()) {
            return Optional.empty();
        }
        String name = m.group(1).trim();
        Matcher legal = TRAILING_LEGAL_FORM.matcher(name);
        while (legal.find()) {
            name = name.substring(0, legal.start()).trim();
            legal = TRAILING_LEGAL_FORM.matcher(name);
        }
        return name.isEmpty() ? Optional.empty() : Optional.of(name.toUpperCase(Locale.ROOT));
    }
}
