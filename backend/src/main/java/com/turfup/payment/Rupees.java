package com.turfup.payment;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/** Formats paise for messages shown to users: 20000 -> "₹200", 120050 -> "₹1,200.50", 10000000 -> "₹1,00,000". */
public final class Rupees {

    private static final Locale INDIA = Locale.forLanguageTag("en-IN");

    private Rupees() {
    }

    public static String format(long paise) {
        NumberFormat f = NumberFormat.getNumberInstance(INDIA);
        f.setMinimumFractionDigits(paise % 100 == 0 ? 0 : 2);
        f.setMaximumFractionDigits(2);
        return "₹" + f.format(BigDecimal.valueOf(paise, 2));
    }
}
