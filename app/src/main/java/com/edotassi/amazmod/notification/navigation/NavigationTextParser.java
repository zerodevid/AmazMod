package com.edotassi.amazmod.notification.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Locale-agnostic reading of the short strings Google Maps puts in its navigation notification.
 *
 * The summary line looks like "23 min · 12 km · 20:45 ETA" in English, but the order of the three
 * fields, the separator and the trailing marker all change with the phone's language. Rather than
 * assuming positions, each part is classified by its shape: something with a unit suffix is a
 * distance, something with a clock is an arrival time, and whatever is left is a duration.
 *
 * Deliberately free of Android types so it can be unit tested on the JVM.
 */
public class NavigationTextParser {

    /** Separators Maps has been seen to use between the summary fields. */
    // MIDDLE DOT, BULLET, DOT OPERATOR, HYPHENATION POINT, BULLET OPERATOR
    private static final String SEPARATORS = "\u00b7\u2022\u22c5\u2027\u2219";

    /** Units are the same tokens across the locales Maps ships, only the numbers get localised. */
    private static final Pattern DISTANCE = Pattern.compile(
            "^\\d+([.,]\\d+)?\\s*(m|km|mi|ft|yd|mts?|kms?|miles?|meters?|metres?)$",
            Pattern.CASE_INSENSITIVE);

    /** 20:45, 8:45 PM, 20.45 — two digits after the separator is what distinguishes it from "1.5 km". */
    private static final Pattern CLOCK = Pattern.compile(
            ".*\\b\\d{1,2}[:.]\\d{2}\\b.*");

    /** Dropped wherever it appears: it marks the arrival time in English but has no equivalent elsewhere. */
    private static final Pattern ETA_MARKER = Pattern.compile(
            "\\bETA\\b", Pattern.CASE_INSENSITIVE);

    /** The clock reading on its own, so "Tiba 00.14" can be reduced to "00.14". */
    private static final Pattern CLOCK_TOKEN = Pattern.compile(
            "\\b\\d{1,2}[:.]\\d{2}\\b(\\s*[AaPp][Mm])?");

    /** The three fields of the summary line, any of which may be empty. */
    public static class Summary {
        public final String ete;
        public final String distance;
        public final String eta;

        Summary(String ete, String distance, String eta) {
            this.ete = ete;
            this.distance = distance;
            this.eta = eta;
        }

        public boolean isEmpty() {
            return ete.isEmpty() && distance.isEmpty() && eta.isEmpty();
        }

        @Override
        public String toString() {
            return "Summary{ete='" + ete + "', distance='" + distance + "', eta='" + eta + "'}";
        }
    }

    private static final Summary EMPTY = new Summary("", "", "");

    /**
     * Splits and classifies the summary line. Returns an empty Summary when the text does not look
     * like a summary at all, so the caller can keep looking elsewhere.
     */
    public static Summary parseSummary(String raw) {
        if (raw == null)
            return EMPTY;

        final List<String> parts = split(normalize(raw));

        // A single chunk means we did not find the separator and cannot tell the fields apart
        if (parts.size() < 2)
            return EMPTY;

        String ete = "";
        String distance = "";
        String eta = "";
        final List<String> unclassified = new ArrayList<>();

        for (String part : parts) {
            if (distance.isEmpty() && isDistance(part)) {
                distance = part;
            } else if (eta.isEmpty() && isClockTime(part)) {
                eta = part;
            } else {
                unclassified.add(part);
            }
        }

        // Whatever is left over is the remaining time, the only field with no recognisable shape
        for (String part : unclassified) {
            if (ete.isEmpty()) {
                ete = part;
            }
        }

        return new Summary(ete, distance, eta);
    }

    /**
     * Classifies a lone value that we already know belongs to the trip summary, such as the
     * subText of the notification. Unlike parseSummary this does not require separators, but it
     * also refuses to guess: text that is neither a distance nor a clock returns empty rather than
     * being filed as a duration.
     *
     * Google Maps on Android 16 puts "Tiba 00.14" here, which is an arrival time wearing a word.
     */
    public static Summary parseSingleField(String raw) {
        if (raw == null)
            return EMPTY;

        final String text = normalize(raw);
        if (text.isEmpty())
            return EMPTY;

        if (isDistance(text))
            return new Summary("", text, "");

        final String clock = extractClock(text);
        if (!clock.isEmpty())
            return new Summary("", "", clock);

        return EMPTY;
    }

    /** Returns just the clock reading inside a longer string, or "" when there is none. */
    public static String extractClock(String text) {
        if (text == null)
            return "";

        final java.util.regex.Matcher matcher = CLOCK_TOKEN.matcher(normalize(text));
        return matcher.find() ? matcher.group().trim() : "";
    }

    /** True for "450 m", "1,2 km", "12 mi". */
    public static boolean isDistance(String text) {
        if (text == null)
            return false;

        return DISTANCE.matcher(normalize(text)).matches();
    }

    /** True for anything containing a clock reading such as "20:45" or "8:45 PM". */
    public static boolean isClockTime(String text) {
        if (text == null)
            return false;

        final String normalized = normalize(text);

        // Checked after isDistance by callers, but guard anyway so "1.5 km" is never a time
        if (isDistance(normalized))
            return false;

        return CLOCK.matcher(normalized).matches();
    }

    /** True when the text carries one of the separators, ie. it looks like a summary line. */
    public static boolean looksLikeSummary(String text) {
        if (text == null)
            return false;

        return split(normalize(text)).size() >= 2;
    }

    /**
     * Collapses the whitespace Maps uses for layout (non-breaking and narrow spaces) and removes
     * the English-only "ETA" marker.
     */
    static String normalize(String raw) {
        String text = raw
                .replace('\u00a0', ' ')   // no-break space
                .replace('\u202f', ' ')   // narrow no-break space
                .replace('\u2009', ' ')   // thin space
                .replace('\n', ' ');

        text = ETA_MARKER.matcher(text).replaceAll(" ");

        return text.trim().replaceAll("\\s+", " ");
    }

    private static List<String> split(String text) {
        final List<String> parts = new ArrayList<>();
        final StringBuilder current = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);

            if (SEPARATORS.indexOf(c) >= 0) {
                addIfNotEmpty(parts, current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        addIfNotEmpty(parts, current.toString());

        return parts;
    }

    private static void addIfNotEmpty(List<String> parts, String candidate) {
        final String trimmed = candidate.trim();
        if (!trimmed.isEmpty())
            parts.add(trimmed);
    }

    /**
     * Formats a distance in metres the way Maps itself would: metres below a kilometre, one
     * decimal up to 100 km, whole kilometres beyond that.
     */
    public static String formatDistanceMetres(long metres) {
        if (metres < 0)
            return "";

        if (metres < 1000)
            return metres + " m";

        final double km = metres / 1000.0;

        if (km < 100)
            return String.format(java.util.Locale.getDefault(), "%.1f km", km);

        return Math.round(km) + " km";
    }

    /**
     * Minutes from the given wall clock until the arrival time, wrapping past midnight.
     *
     * The current time is passed in rather than read here so this stays a pure function.
     *
     * @param clock arrival reading such as "14.37" or "8:45 PM"
     * @return minutes remaining, or -1 when the arrival time could not be read
     */
    public static int minutesUntil(String clock, int nowHour, int nowMinute) {
        final String reading = extractClock(clock);
        if (reading.isEmpty())
            return -1;

        try {
            final boolean pm = reading.toUpperCase(java.util.Locale.ROOT).contains("PM");
            final boolean am = reading.toUpperCase(java.util.Locale.ROOT).contains("AM");
            final String digits = reading.replaceAll("[^0-9:.]", "");
            final String[] parts = digits.split("[:.]");
            if (parts.length < 2)
                return -1;

            int hour = Integer.parseInt(parts[0]);
            final int minute = Integer.parseInt(parts[1]);

            if (pm && hour < 12)
                hour += 12;
            if (am && hour == 12)
                hour = 0;

            if (hour > 23 || minute > 59)
                return -1;

            int minutes = (hour * 60 + minute) - (nowHour * 60 + nowMinute);
            if (minutes < 0)
                minutes += 24 * 60;   // arrival is tomorrow

            return minutes;

        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * "45 mnt", "8 j 45 mnt".
     *
     * The unit words are passed in rather than baked in: this string is built on the phone and sent
     * to the watch as finished text, so it has to be in the phone owner's language, and this class
     * stays free of Android resources.
     */
    public static String formatDuration(int minutes, String hourUnit, String minuteUnit) {
        if (minutes < 0)
            return "";

        if (minutes < 60)
            return minutes + " " + minuteUnit;

        final int hours = minutes / 60;
        final int rest = minutes % 60;

        return (rest == 0)
                ? (hours + " " + hourUnit)
                : (hours + " " + hourUnit + " " + rest + " " + minuteUnit);
    }

    /**
     * Compass bearings for the directions Maps names in its instructions.
     *
     * Ordered longest first on purpose: "timur laut" has to be matched before "timur", and
     * "barat daya" before "barat", or the compound directions collapse into the simple ones.
     */
    private static final String[][] BEARINGS = {
            {"timur laut", "45"},   {"northeast", "45"},  {"north east", "45"},
            {"barat laut", "315"},  {"northwest", "315"}, {"north west", "315"},
            {"barat daya", "225"},  {"southwest", "225"}, {"south west", "225"},
            {"tenggara", "135"},    {"southeast", "135"}, {"south east", "135"},
            {"utara", "0"},         {"north", "0"},
            {"selatan", "180"},     {"south", "180"},
            {"timur", "90"},        {"east", "90"},
            {"barat", "270"},       {"west", "270"},
    };

    /**
     * Reads the compass bearing out of an instruction such as "Ke arah timur" or "Head northeast".
     *
     * Maps only names a direction while there is no specific manoeuvre to give - heading off at the
     * start of a trip, or after losing the route - which is exactly when a compass is worth having.
     *
     * @return degrees clockwise from north, or -1 when the instruction names no direction
     */
    public static int bearingOf(String instruction) {
        if (instruction == null)
            return -1;

        final String text = normalize(instruction).toLowerCase(java.util.Locale.ROOT);
        if (text.isEmpty())
            return -1;

        for (String[] entry : BEARINGS) {
            if (containsWord(text, entry[0]))
                return Integer.parseInt(entry[1]);
        }

        return -1;
    }

    /** Whole-word match, so "baratang" never counts as "barat". */
    private static boolean containsWord(String text, String word) {
        final int index = text.indexOf(word);
        if (index < 0)
            return false;

        final boolean startOk = (index == 0) || !Character.isLetter(text.charAt(index - 1));
        final int after = index + word.length();
        final boolean endOk = (after >= text.length()) || !Character.isLetter(text.charAt(after));

        return startOk && endOk;
    }
}
