package com.edotassi.amazmod.notification.navigation;

import android.text.Spanned;
import android.text.style.StyleSpan;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a Spanned into runs of text that either carry a given StyleSpan style or do not.
 *
 * Google Maps writes the turn instruction as a single CharSequence where the road name is
 * Typeface.BOLD and the surrounding words ("Turn right onto", "towards …") are Typeface.NORMAL,
 * so splitting by style is what separates the road from its description.
 *
 * Ported from ParserHelper.kt of maisonsmd/esp32-google-maps (MIT).
 */
public class SpanSplitter {

    /** A run of text and whether it matched the style we were splitting on. */
    public static class Segment {
        public final String text;
        public final boolean isKeySpan;

        Segment(String text, boolean isKeySpan) {
            this.text = text;
            this.isKeySpan = isKeySpan;
        }

        @Override
        public String toString() {
            return (isKeySpan ? "[key]" : "[   ]") + text;
        }
    }

    private static class Span {
        final int begin;
        final int end;
        final int style;

        Span(int begin, int end, int style) {
            this.begin = begin;
            this.end = end;
            this.style = style;
        }
    }

    private static String substring(Spanned input, int start, int end) {
        return input.subSequence(start, end).toString();
    }

    private static List<Span> findSpans(Spanned input) {
        final List<Span> results = new ArrayList<>();
        final int len = input.length();

        int spanBegin = 0;
        int spanEnd = 0;

        while (spanEnd < len) {
            spanEnd = input.nextSpanTransition(spanBegin, len, StyleSpan.class);
            final StyleSpan[] spans = input.getSpans(spanBegin, spanEnd, StyleSpan.class);

            if (spans.length > 0)
                results.add(new Span(spanBegin, spanEnd, spans[0].getStyle()));
            else
                results.add(new Span(spanBegin, spanEnd, android.graphics.Typeface.NORMAL));

            spanBegin = spanEnd;
        }

        return results;
    }

    /**
     * @param keyStyle       the Typeface style that marks the "key" segments
     * @param minSpanLength  ignore styled runs shorter than this, which drops stray single
     *                       characters that Maps sometimes styles (eg. the "/" separator)
     */
    public static List<Segment> splitByStyleSpan(Spanned input, int keyStyle, int minSpanLength) {
        final List<Segment> result = new ArrayList<>();
        final List<Span> spans = findSpans(input);

        int begin = 0;
        int end;
        boolean previousSegmentMatched = false;

        for (int i = 0; i < spans.size(); i++) {
            final Span span = spans.get(i);
            final String segment = substring(input, span.begin, span.end);

            final boolean segmentMatched =
                    (span.style == keyStyle) && (segment.trim().length() >= minSpanLength);

            if (segmentMatched != previousSegmentMatched) {
                end = span.begin;
                final String prevSegment = substring(input, begin, end).trim();
                if (!prevSegment.isEmpty())
                    result.add(new Segment(prevSegment, previousSegmentMatched));
                begin = end;
            }

            if (i == spans.size() - 1) {
                end = span.end;
                final String prevSegment = substring(input, begin, end).trim();
                if (!prevSegment.isEmpty())
                    result.add(new Segment(prevSegment, segmentMatched));
            }

            previousSegmentMatched = segmentMatched;
        }

        return result;
    }
}
