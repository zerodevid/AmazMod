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
}
