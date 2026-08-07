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
}
