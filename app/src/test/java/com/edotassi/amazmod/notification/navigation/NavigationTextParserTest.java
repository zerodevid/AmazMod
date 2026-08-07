package com.edotassi.amazmod.notification.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The summary line is the part of the Maps notification whose wording changes most between
 * languages, so it is pinned down here rather than trusted to hold.
 */
public class NavigationTextParserTest {

    @Test
    public void parsesEnglishSummary() {
        NavigationTextParser.Summary summary =
                NavigationTextParser.parseSummary("23 min \u00b7 12 km \u00b7 20:45 ETA");

        assertEquals("23 min", summary.ete);
        assertEquals("12 km", summary.distance);
        assertEquals("20:45", summary.eta);
    }

    @Test
    public void parsesIndonesianSummary() {
        // Indonesian Maps has no "ETA" marker and writes the duration differently
        NavigationTextParser.Summary summary =
                NavigationTextParser.parseSummary("23 mnt \u00b7 12 km \u00b7 20.45");

        assertEquals("23 mnt", summary.ete);
        assertEquals("12 km", summary.distance);
        assertEquals("20.45", summary.eta);
    }

    @Test
    public void fieldOrderDoesNotMatter() {
        // Classification is by shape, so a locale that reorders the fields still works
        NavigationTextParser.Summary summary =
                NavigationTextParser.parseSummary("20:45 \u00b7 23 min \u00b7 12 km");

        assertEquals("23 min", summary.ete);
        assertEquals("12 km", summary.distance);
        assertEquals("20:45", summary.eta);
    }

    @Test
    public void parsesTwelveHourClock() {
        NavigationTextParser.Summary summary =
                NavigationTextParser.parseSummary("1 hr 5 min \u00b7 42 mi \u00b7 8:45 PM ETA");

        assertEquals("1 hr 5 min", summary.ete);
        assertEquals("42 mi", summary.distance);
        assertEquals("8:45 PM", summary.eta);
    }

    @Test
    public void handlesNonBreakingSpaces() {
        // Maps pads the line with no-break and narrow spaces for layout
        NavigationTextParser.Summary summary =
                NavigationTextParser.parseSummary("23\u00a0min \u00b7 12\u202fkm \u00b7 20:45\u00a0ETA");

        assertEquals("23 min", summary.ete);
        assertEquals("12 km", summary.distance);
        assertEquals("20:45", summary.eta);
    }

    @Test
    public void acceptsAlternativeSeparators() {
        NavigationTextParser.Summary summary =
                NavigationTextParser.parseSummary("23 min \u2022 12 km \u2022 20:45");

        assertEquals("23 min", summary.ete);
        assertEquals("12 km", summary.distance);
        assertEquals("20:45", summary.eta);
    }

    @Test
    public void parsesPartialSummary() {
        NavigationTextParser.Summary summary =
                NavigationTextParser.parseSummary("12 km \u00b7 20:45");

        assertEquals("", summary.ete);
        assertEquals("12 km", summary.distance);
        assertEquals("20:45", summary.eta);
    }

    @Test
    public void rejectsTextThatIsNotASummary() {
        assertTrue(NavigationTextParser.parseSummary("Turn right onto Jl. Merdeka").isEmpty());
        assertTrue(NavigationTextParser.parseSummary("450 m").isEmpty());
        assertTrue(NavigationTextParser.parseSummary("").isEmpty());
        assertTrue(NavigationTextParser.parseSummary(null).isEmpty());
    }

    @Test
    public void recognisesDistances() {
        assertTrue(NavigationTextParser.isDistance("450 m"));
        assertTrue(NavigationTextParser.isDistance("12 km"));
        assertTrue(NavigationTextParser.isDistance("1,2 km"));   // comma decimal separator
        assertTrue(NavigationTextParser.isDistance("1.5 km"));
        assertTrue(NavigationTextParser.isDistance("300ft"));
        assertTrue(NavigationTextParser.isDistance("42 MI"));
    }

    @Test
    public void rejectsNonDistances() {
        assertFalse(NavigationTextParser.isDistance("20:45"));
        assertFalse(NavigationTextParser.isDistance("23 min"));
        assertFalse(NavigationTextParser.isDistance("Turn right"));
        assertFalse(NavigationTextParser.isDistance(""));
        assertFalse(NavigationTextParser.isDistance(null));
    }

    @Test
    public void distanceIsNeverMistakenForAClock() {
        // "1.5 km" has a dot and digits but only one digit after the dot
        assertFalse(NavigationTextParser.isClockTime("1.5 km"));
        assertFalse(NavigationTextParser.isClockTime("12.30 km"));
        assertTrue(NavigationTextParser.isClockTime("20.45"));
        assertTrue(NavigationTextParser.isClockTime("8:45 PM"));
    }

    @Test
    public void detectsSummaryShape() {
        assertTrue(NavigationTextParser.looksLikeSummary("23 min \u00b7 12 km \u00b7 20:45"));
        assertTrue(NavigationTextParser.looksLikeSummary("12 km \u00b7 20:45"));
        assertFalse(NavigationTextParser.looksLikeSummary("Turn right onto Jl. Merdeka"));
        assertFalse(NavigationTextParser.looksLikeSummary("450 m"));
    }

    @Test
    public void parsesTheSingleFieldAndroid16MapsGives() {
        // Real value observed from Google Maps 26 on Android 16, locale id-ID
        NavigationTextParser.Summary summary =
                NavigationTextParser.parseSingleField("Tiba 00.14");

        assertEquals("", summary.ete);
        assertEquals("", summary.distance);
        assertEquals("00.14", summary.eta);
    }

    @Test
    public void singleFieldRecognisesADistance() {
        NavigationTextParser.Summary summary = NavigationTextParser.parseSingleField("450 m");

        assertEquals("450 m", summary.distance);
        assertEquals("", summary.eta);
    }

    @Test
    public void singleFieldRefusesToGuess() {
        // An instruction is neither a distance nor a clock, so nothing should be invented from it
        assertTrue(NavigationTextParser.parseSingleField("Ke arah barat").isEmpty());
        assertTrue(NavigationTextParser.parseSingleField("").isEmpty());
        assertTrue(NavigationTextParser.parseSingleField(null).isEmpty());
    }

    @Test
    public void extractsClockFromSurroundingWords() {
        assertEquals("00.14", NavigationTextParser.extractClock("Tiba 00.14"));
        assertEquals("20:45", NavigationTextParser.extractClock("20:45 ETA"));
        assertEquals("8:45 PM", NavigationTextParser.extractClock("Arrive 8:45 PM"));
        assertEquals("", NavigationTextParser.extractClock("Ke arah barat"));
        assertEquals("", NavigationTextParser.extractClock("1.5 km"));
    }

    @Test
    public void formatsTheRouteLengthObservedOnAndroid16() {
        // progressMax 381218 minus progress 4, straight from the phone
        assertEquals("381 km", NavigationTextParser.formatDistanceMetres(381218 - 4));
    }

    @Test
    public void formatsDistancesAcrossTheRanges() {
        assertEquals("450 m", NavigationTextParser.formatDistanceMetres(450));
        assertEquals("999 m", NavigationTextParser.formatDistanceMetres(999));
        assertEquals("1.0 km", NavigationTextParser.formatDistanceMetres(1000).replace(',', '.'));
        assertEquals("12.3 km", NavigationTextParser.formatDistanceMetres(12340).replace(',', '.'));
        assertEquals("381 km", NavigationTextParser.formatDistanceMetres(381214));
        assertEquals("", NavigationTextParser.formatDistanceMetres(-1));
    }

    @Test
    public void computesRemainingTimeFromTheArrivalClock() {
        // The real case: log stamped 05:52, Maps said "Tiba 14.37"
        assertEquals(8 * 60 + 45, NavigationTextParser.minutesUntil("Tiba 14.37", 5, 52));
    }

    @Test
    public void remainingTimeWrapsPastMidnight() {
        // Arriving 00.30 when it is 23:50 is 40 minutes away, not minus a day
        assertEquals(40, NavigationTextParser.minutesUntil("Tiba 00.30", 23, 50));
    }

    @Test
    public void remainingTimeUnderstandsTwelveHourClocks() {
        assertEquals(105, NavigationTextParser.minutesUntil("8:45 PM", 19, 0));
        assertEquals(30, NavigationTextParser.minutesUntil("12:30 AM", 0, 0));
    }

    @Test
    public void remainingTimeRefusesUnreadableInput() {
        assertEquals(-1, NavigationTextParser.minutesUntil("Ke arah timur", 5, 52));
        assertEquals(-1, NavigationTextParser.minutesUntil("", 5, 52));
        assertEquals(-1, NavigationTextParser.minutesUntil(null, 5, 52));
    }

    @Test
    public void formatsDurationsInWhateverUnitsItIsGiven() {
        assertEquals("45 mnt", NavigationTextParser.formatDuration(45, "j", "mnt"));
        assertEquals("8 j 45 mnt", NavigationTextParser.formatDuration(525, "j", "mnt"));
        assertEquals("2 j", NavigationTextParser.formatDuration(120, "j", "mnt"));
        assertEquals("", NavigationTextParser.formatDuration(-1, "j", "mnt"));

        // Same numbers, English units: nothing about the language is baked in
        assertEquals("45 min", NavigationTextParser.formatDuration(45, "h", "min"));
        assertEquals("8 h 45 min", NavigationTextParser.formatDuration(525, "h", "min"));
    }

    @Test
    public void readsTheBearingFromIndonesianInstructions() {
        // The exact instruction the phone produced
        assertEquals(90, NavigationTextParser.bearingOf("Ke arah timur"));
        assertEquals(0, NavigationTextParser.bearingOf("Ke arah utara"));
        assertEquals(180, NavigationTextParser.bearingOf("Ke arah selatan"));
        assertEquals(270, NavigationTextParser.bearingOf("Ke arah barat"));
    }

    @Test
    public void compoundDirectionsBeatTheirParts() {
        // "timur laut" must not collapse into "timur", nor "barat daya" into "barat"
        assertEquals(45, NavigationTextParser.bearingOf("Ke arah timur laut"));
        assertEquals(315, NavigationTextParser.bearingOf("Ke arah barat laut"));
        assertEquals(225, NavigationTextParser.bearingOf("Ke arah barat daya"));
        assertEquals(135, NavigationTextParser.bearingOf("Ke arah tenggara"));
    }

    @Test
    public void readsEnglishBearingsToo() {
        assertEquals(90, NavigationTextParser.bearingOf("Head east"));
        assertEquals(45, NavigationTextParser.bearingOf("Head northeast"));
        assertEquals(315, NavigationTextParser.bearingOf("Head north west"));
    }

    @Test
    public void instructionsWithoutADirectionHaveNoBearing() {
        assertEquals(-1, NavigationTextParser.bearingOf("Belok kiri"));
        assertEquals(-1, NavigationTextParser.bearingOf("Jl. Jenderal Sudirman"));
        assertEquals(-1, NavigationTextParser.bearingOf(""));
        assertEquals(-1, NavigationTextParser.bearingOf(null));
    }

    @Test
    public void directionsMustBeWholeWords() {
        // A road name that merely starts with a direction is not a heading
        assertEquals(-1, NavigationTextParser.bearingOf("Jl. Baratang"));
        assertEquals(-1, NavigationTextParser.bearingOf("Easterly Road"));
    }
}
