package com.turfup.pricing;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeSet;

/**
 * The price rules that apply to one court on one operating weekday. Each minute is priced by the
 * day-specific rule covering it, or else by the general rule covering it. A range spanning several rates
 * is split at the rule boundaries and each part is charged pro rata.
 *
 * <p>Integer arithmetic only: the total is {@code sum(rate * minutes)} divided by 60 once at the end,
 * rounded half up to the nearest paisa.
 */
public record DayPricing(List<PriceRule> dayRules, List<PriceRule> generalRules) {

    /**
     * Price in paise for {@code [startMinute, endMinute)}.
     *
     * @throws PriceNotConfiguredException if some minute has no rule
     */
    public long price(int startMinute, int endMinute) {
        return find(startMinute, endMinute).orElseThrow(PriceNotConfiguredException::new);
    }

    /** Like {@link #price}, but empty if some minute has no rule. */
    public OptionalLong find(int startMinute, int endMinute) {
        if (endMinute <= startMinute) {
            throw new IllegalArgumentException("endMinute must be after startMinute");
        }
        TreeSet<Integer> cuts = new TreeSet<>(List.of(startMinute, endMinute));
        for (List<PriceRule> rules : List.of(dayRules, generalRules)) {
            for (PriceRule r : rules) {
                for (int edge : new int[] {r.startMinute(), r.endMinute()}) {
                    if (edge > startMinute && edge < endMinute) {
                        cuts.add(edge);
                    }
                }
            }
        }

        long rateMinutes = 0;
        Integer from = null;
        for (int to : cuts) {
            if (from != null) {
                int minute = from;
                Optional<PriceRule> rule = covering(dayRules, minute).or(() -> covering(generalRules, minute));
                if (rule.isEmpty()) {
                    return OptionalLong.empty();
                }
                rateMinutes += rule.get().rate() * (to - from);
            }
            from = to;
        }
        return OptionalLong.of((rateMinutes + 30) / 60);
    }

    private static Optional<PriceRule> covering(List<PriceRule> rules, int minute) {
        return rules.stream().filter(r -> r.covers(minute)).findFirst();
    }
}
