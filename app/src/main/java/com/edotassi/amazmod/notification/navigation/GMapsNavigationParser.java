package com.edotassi.amazmod.notification.navigation;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.Spanned;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.List;

import amazmod.com.transport.data.NavigationData;

/**
 * Extracts turn-by-turn data out of the ongoing Google Maps notification.
 *
 * Maps exposes no API for the current navigation step, so the data has to be read back out of the
 * notification it posts. Three independent strategies are tried in order of how likely they are to
 * survive a Maps update, and each one only fills in the fields the previous ones left empty:
 *
 *   1. Notification.extras — public framework API, no inflation, nothing app-specific.
 *   2. The notification's RemoteViews, looked up by view id. The ids used ("title", "text",
 *      "header_text", "right_icon") belong to the AOSP notification template rather than to Maps,
 *      so this keeps working as long as Maps posts a standard-style notification.
 *   3. Shape heuristics over every text and image view in the inflated tree: whichever text parses
 *      as a distance is the distance, whichever splits into a summary is the summary, and so on.
 *      This survives Maps moving to a fully custom layout with ids we have never heard of.
 *
 * If all three come up empty the caller falls back to forwarding the notification as plain text,
 * which is what AmazMod did before this feature existed.
 *
 * Strategy 2 is ported from GMapsNotification.kt of maisonsmd/esp32-google-maps (MIT).
 */
public class GMapsNavigationParser {

    public static final String GMAPS_PACKAGE = "com.google.android.apps.maps";

    // AOSP notification template ids (android:id/...), not Google Maps' own
    private static final String VIEW_INSTRUCTION = "text";        // "Turn right onto Jl. Merdeka"
    private static final String VIEW_HEADER = "header_text";      // "23 min · 12 km · 20:45 ETA"
    private static final String VIEW_TITLE = "title";             // "450 m"
    private static final String VIEW_ICON = "right_icon";         // manoeuvre arrow

    // An instruction is a sentence; a distance or a clock is not. Used to tell them apart when we
    // are down to guessing.
    private static final int MIN_INSTRUCTION_LENGTH = 8;
    // Below this an ImageView is a status glyph rather than the manoeuvre arrow
    private static final int MIN_ICON_SIZE = 16;

    private final Context context;
    private final Context mapsContext;
    private final Notification notification;
    private final NavigationData navigationData;

    // Which strategy supplied what, logged when parsing goes wrong
    private final List<String> sources = new ArrayList<>();

    // TEMPORARY (test build): every text the notification renders, so fields we have not located
    // yet can be found. Remove with the probe.
    private final List<String> seenTexts = new ArrayList<>();

    // True once we managed to split an instruction into road plus description
    private boolean instructionStructured = false;

    public GMapsNavigationParser(Context context, StatusBarNotification sbn)
            throws android.content.pm.PackageManager.NameNotFoundException {
        this.context = context;
        this.notification = sbn.getNotification();
        this.mapsContext = context.createPackageContext(sbn.getPackageName(),
                Context.CONTEXT_IGNORE_SECURITY);
        this.navigationData = new NavigationData();
        this.navigationData.setTimestamp(sbn.getPostTime());
    }

    /**
     * @return the parsed data, or a NavigationData whose isEmpty() is true when nothing usable
     *         could be extracted by any strategy.
     */
    public NavigationData parse() {
        parseFromExtras();
        parseFromRemoteViews();

        // Maps is not giving a manoeuvre: no styled instruction, no distance and no arrow. That is
        // what "Rerouting..." and similar status lines look like, in any language.
        navigationData.setRerouting(!instructionStructured
                && navigationData.getDistanceToNext().isEmpty()
                && navigationData.getIconHash().isEmpty());

        // TEMPORARY (test build): logged at error level so it survives the log-level filtering
        // on this phone. Revert to warn/debug before committing.
        if (navigationData.isEmpty())
            probe("NO DATA tried=" + sources
                    + "\n          views=" + seenTexts + "\n          extras=" + dumpExtras());
        else
            probe("OK sources=" + sources + " | data=" + navigationData
                    + "\n          views=" + seenTexts + "\n          extras=" + dumpExtras());

        return navigationData;
    }

    /*
     * Strategy 1: the notification's own extras
     */

    private void parseFromExtras() {
        final Bundle extras = notification.extras;
        if (extras == null)
            return;

        try {
            // Read as CharSequence so the style spans that separate road from description survive
            applyInstruction(extras.getCharSequence(Notification.EXTRA_TEXT), "extras.text");
            applyDistanceToNext(text(extras.getCharSequence(Notification.EXTRA_TITLE)), "extras.title");

            // Android 16 promoted-ongoing notifications carry their headline metric here, which for
            // navigation is the distance to the next manoeuvre
            applyDistanceToNext(text(extras.getCharSequence("android.shortCriticalText")),
                    "extras.shortCriticalText");

            applySummary(text(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)), "extras.subText");
            applyIcon(largeIconBitmap(), "extras.largeIcon");

            // On ProgressStyle there is no EXTRA_TEXT at all and the title is the manoeuvre, so it
            // becomes the instruction once we know it is not a distance
            applyInstruction(extras.getCharSequence(Notification.EXTRA_TITLE), "extras.title");

        } catch (Exception e) {
            Logger.error("GMapsNavigationParser parseFromExtras exception: " + e.getMessage());
        }
    }

    private Bitmap largeIconBitmap() {
        try {
            // getLargeIcon() is the modern accessor; it is what the template renders as right_icon
            if (notification.getLargeIcon() != null) {
                final Drawable drawable = notification.getLargeIcon().loadDrawable(context);
                if (drawable instanceof BitmapDrawable)
                    return ((BitmapDrawable) drawable).getBitmap();
            }
        } catch (Exception e) {
            Logger.error("GMapsNavigationParser largeIconBitmap exception: " + e.getMessage());
        }
        return null;
    }

    /*
     * Strategy 2 and 3: the notification's RemoteViews
     */

    private void parseFromRemoteViews() {
        if (isComplete())
            return;

        // The collapsed and expanded layouts do not always carry the same fields, so walk both
        final RemoteViews normalContent = getContentView(false);
        final RemoteViews bestContent = getContentView(true);

        parseViewTree(normalContent);

        if (bestContent != null && bestContent != normalContent)
            parseViewTree(bestContent);
    }

    private void parseViewTree(RemoteViews remoteViews) {
        if (remoteViews == null || isComplete())
            return;

        final ViewGroup group;
        try {
            group = inflate(remoteViews);
        } catch (Exception e) {
            Logger.error("GMapsNavigationParser inflate exception: " + e.getMessage());
            return;
        }

        if (group == null) {
            Logger.warn("GMapsNavigationParser could not inflate notification view");
            return;
        }

        collectSeenTexts(group);
        parseByViewId(group);
        parseByShape(group);
    }

    /** Strategy 2: pick the views the AOSP notification template is known to use. */
    private void parseByViewId(ViewGroup group) {
        applyInstruction(textOf(findChildByName(group, VIEW_INSTRUCTION, TextView.class)), "view.text");
        applyDistanceToNext(text(textOf(findChildByName(group, VIEW_TITLE, TextView.class))), "view.title");
        applySummary(text(textOf(findChildByName(group, VIEW_HEADER, TextView.class))), "view.header_text");
        applyIcon(bitmapOf(findChildByName(group, VIEW_ICON, ImageView.class)), "view.right_icon");
    }

    /**
     * Strategy 3: forget the ids, look at what the values themselves look like.
     *
     * Ordering matters. The summary is claimed first because it is the only text carrying a
     * separator, then the distance because it has a recognisable unit, and only what is left can be
     * the instruction.
     */
    private void parseByShape(ViewGroup group) {
        if (isComplete())
            return;

        final List<TextView> textViews = new ArrayList<>();
        final List<ImageView> imageViews = new ArrayList<>();
        collectViews(group, textViews, imageViews);

        for (TextView textView : textViews) {
            final String value = text(textOf(textView));
            if (NavigationTextParser.looksLikeSummary(value))
                applySummary(value, "shape.summary");
        }

        for (TextView textView : textViews) {
            final String value = text(textOf(textView));
            if (NavigationTextParser.isDistance(value))
                applyDistanceToNext(value, "shape.distance");
        }

        // The instruction is the longest remaining text, preferring one that carries style spans
        CharSequence best = null;
        for (TextView textView : textViews) {
            final CharSequence candidate = textOf(textView);
            if (!isInstructionCandidate(candidate))
                continue;

            if (best == null
                    || (candidate instanceof Spanned && !(best instanceof Spanned))
                    || candidate.length() > best.length())
                best = candidate;
        }
        applyInstruction(best, "shape.instruction");

        // The manoeuvre arrow is the biggest roughly-square bitmap on the notification
        Bitmap bestIcon = null;
        for (ImageView imageView : imageViews) {
            final Bitmap candidate = bitmapOf(imageView);
            if (!isIconCandidate(candidate))
                continue;

            if (bestIcon == null || area(candidate) > area(bestIcon))
                bestIcon = candidate;
        }
        applyIcon(bestIcon, "shape.icon");
    }

    private boolean isInstructionCandidate(CharSequence candidate) {
        final String value = text(candidate);

        return value.length() >= MIN_INSTRUCTION_LENGTH
                && !NavigationTextParser.isDistance(value)
                && !NavigationTextParser.looksLikeSummary(value)
                && !value.equals(navigationData.getDistanceToNext());
    }

    private boolean isIconCandidate(Bitmap candidate) {
        if (candidate == null || candidate.isRecycled())
            return false;

        if (candidate.getWidth() < MIN_ICON_SIZE || candidate.getHeight() < MIN_ICON_SIZE)
            return false;

        // Manoeuvre arrows are square; a wide banner is something else
        final float ratio = (float) candidate.getWidth() / candidate.getHeight();
        return ratio > 0.5f && ratio < 2.0f;
    }

    private static int area(Bitmap bitmap) {
        return bitmap.getWidth() * bitmap.getHeight();
    }

    /*
     * Field setters, each a no-op once the field has a value, so earlier strategies win
     */

    /**
     * Only text that actually reads as a distance is accepted. Older Maps builds put "450 m" in the
     * notification title, but on Android 16's ProgressStyle template the title holds the manoeuvre
     * itself ("Ke arah barat"), and filing that as a distance was silently wrong.
     */
    private void applyDistanceToNext(String value, String source) {
        if (!navigationData.getDistanceToNext().isEmpty() || value.isEmpty())
            return;

        if (!NavigationTextParser.isDistance(value))
            return;

        navigationData.setDistanceToNext(value);
        sources.add("distance=" + source);
    }

    private void applySummary(String value, String source) {
        if (value.isEmpty())
            return;

        final boolean haveSummary = !navigationData.getEta().isEmpty()
                || !navigationData.getEte().isEmpty()
                || !navigationData.getTotalDistance().isEmpty();
        if (haveSummary)
            return;

        NavigationTextParser.Summary summary = NavigationTextParser.parseSummary(value);

        // Android 16's Maps gives a single field ("Tiba 00.14") rather than the old
        // "23 min - 12 km - 20:45" line, so fall back to classifying it on its own.
        if (summary.isEmpty())
            summary = NavigationTextParser.parseSingleField(value);

        if (summary.isEmpty())
            return;

        navigationData.setEte(summary.ete);
        navigationData.setTotalDistance(summary.distance);
        navigationData.setEta(summary.eta);
        sources.add("summary=" + source);
    }

    private void applyIcon(Bitmap bitmap, String source) {
        if (!navigationData.getIconHash().isEmpty() || bitmap == null || bitmap.isRecycled())
            return;

        final byte[] png = NavigationIconHelper.toPng(bitmap);
        if (png.length == 0)
            return;

        navigationData.setIcon(png);
        navigationData.setIconHash(NavigationIconHelper.hash(png));
        sources.add("icon=" + source);
    }

    /**
     * The instruction is a Spanned where the road name is bold. Everything up to the first bold run
     * is the road; a second bold run ("towards …") starts the description.
     *
     * A plain String means Maps is showing a status such as "Rerouting…" instead, or that the
     * source we read from dropped the spans, in which case the whole line becomes the road.
     */
    private void applyInstruction(CharSequence instruction, String source) {
        if (!navigationData.getNextRoad().isEmpty() || instruction == null)
            return;

        final String plain = text(instruction);
        if (plain.isEmpty())
            return;

        // No spans: either a status line, or a source that dropped the styling. Either way the
        // whole line is the best we can show.
        if (!(instruction instanceof Spanned)) {
            navigationData.setNextRoad(plain);
            sources.add("instruction(plain)=" + source);
            return;
        }

        final List<SpanSplitter.Segment> segments =
                SpanSplitter.splitByStyleSpan((Spanned) instruction, Typeface.NORMAL, 2);

        if (segments.isEmpty()) {
            navigationData.setNextRoad(plain);
            sources.add("instruction(unsplit)=" + source);
            return;
        }

        final StringBuilder road = new StringBuilder(segments.get(0).text);
        final StringBuilder description = new StringBuilder();

        int descriptionStart = -1;
        for (int i = 1; i < segments.size(); i++) {
            final SpanSplitter.Segment segment = segments.get(i);
            if (segment.isKeySpan && !segment.text.trim().equals("/")) {
                descriptionStart = i;
                break;
            }
        }

        final int roadEnd = (descriptionStart == -1) ? segments.size() : descriptionStart;

        for (int i = 1; i < roadEnd; i++)
            road.append(" ").append(segments.get(i).text);

        if (descriptionStart != -1) {
            for (int i = descriptionStart; i < segments.size(); i++) {
                if (description.length() > 0)
                    description.append(" ");
                description.append(segments.get(i).text);
            }
        }

        navigationData.setNextRoad(road.toString().trim());
        navigationData.setNextRoadDescription(description.toString().trim());
        instructionStructured = true;
        sources.add("instruction=" + source);
    }

    /** Everything worth having is filled in, so later strategies can be skipped entirely. */
    private boolean isComplete() {
        return !navigationData.getNextRoad().isEmpty()
                && !navigationData.getDistanceToNext().isEmpty()
                && !navigationData.getEta().isEmpty()
                && !navigationData.getIconHash().isEmpty();
    }

    /*
     * View plumbing
     */

    private RemoteViews getContentView(boolean preferBig) {
        try {
            final Notification.Builder builder = Notification.Builder.recoverBuilder(context, notification);

            if (preferBig) {
                final RemoteViews big = builder.createBigContentView();
                if (big != null)
                    return big;
            }

            return builder.createContentView();

        } catch (Exception e) {
            Logger.error("GMapsNavigationParser getContentView exception: " + e.getMessage());
            return null;
        }
    }

    private ViewGroup inflate(RemoteViews remoteViews) {
        final LayoutInflater layoutInflater =
                (LayoutInflater) mapsContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        if (layoutInflater == null)
            return null;

        final View inflated = layoutInflater.inflate(remoteViews.getLayoutId(), null);
        if (!(inflated instanceof ViewGroup))
            return null;

        final ViewGroup viewGroup = (ViewGroup) inflated;
        // reapply() replays the RemoteViews actions onto the real views, which is what actually
        // puts the current navigation text into them
        remoteViews.reapply(mapsContext, viewGroup);

        return viewGroup;
    }

    /** TEMPORARY (test build): records what the notification actually renders. */
    private void collectSeenTexts(ViewGroup group) {
        final List<TextView> textViews = new ArrayList<>();
        final List<ImageView> imageViews = new ArrayList<>();
        collectViews(group, textViews, imageViews);

        for (TextView textView : textViews) {
            final String value = text(textOf(textView));
            if (!value.isEmpty()) {
                final String entry = getEntryName(textView) + "='" + value + "'";
                if (!seenTexts.contains(entry))
                    seenTexts.add(entry);
            }
        }
    }

    private void collectViews(ViewGroup group, List<TextView> textViews, List<ImageView> imageViews) {
        for (int i = 0; i < group.getChildCount(); i++) {
            final View child = group.getChildAt(i);

            if (child instanceof TextView && child.getVisibility() == View.VISIBLE)
                textViews.add((TextView) child);
            else if (child instanceof ImageView && child.getVisibility() == View.VISIBLE)
                imageViews.add((ImageView) child);

            if (child instanceof ViewGroup)
                collectViews((ViewGroup) child, textViews, imageViews);
        }
    }

    /** Depth-first search for a view whose resource entry name matches. */
    private View findChildByName(ViewGroup group, String name, Class<?> type) {
        for (int i = 0; i < group.getChildCount(); i++) {
            final View child = group.getChildAt(i);

            if (type.isInstance(child) && name.equals(getEntryName(child)))
                return child;

            if (child instanceof ViewGroup) {
                final View found = findChildByName((ViewGroup) child, name, type);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /**
     * TEMPORARY (test build): writes straight to a file next to the app's own logs.
     * tinylog's file writer proved unreliable here and this phone's ROM drops app logcat output,
     * so this is the only channel that reliably survives. Remove before committing.
     */
    private void probe(String message) {
        try {
            final java.io.File dir = context.getExternalFilesDir(null);
            if (dir == null)
                return;

            final java.io.FileWriter writer = new java.io.FileWriter(new java.io.File(dir, "navtest.log"), true);
            writer.write(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date()) + "  " + message + "\n\n");
            writer.close();
        } catch (Exception e) {
            Logger.error("probe failed: " + e.getMessage());
        }
    }

    /** TEMPORARY (test build): shows which notification extras Maps actually populated. */
    private String dumpExtras() {
        try {
            final Bundle extras = notification.extras;
            if (extras == null)
                return "null";

            final StringBuilder sb = new StringBuilder();
            for (String key : extras.keySet()) {
                final Object value = extras.get(key);
                if (value instanceof CharSequence)
                    sb.append(key).append("='").append(value).append("' ");
                else if (value != null)
                    sb.append(key).append("=<").append(value.getClass().getSimpleName()).append("> ");
            }
            return sb.toString();
        } catch (Exception e) {
            return "dump failed: " + e.getMessage();
        }
    }

    private String getEntryName(View view) {
        try {
            if (view.getId() > 0)
                return mapsContext.getResources().getResourceEntryName(view.getId());
        } catch (Exception ignored) {
            // Ids that belong to no resource simply have no entry name
        }
        return "";
    }

    private static CharSequence textOf(View view) {
        return (view instanceof TextView) ? ((TextView) view).getText() : null;
    }

    private static Bitmap bitmapOf(View view) {
        if (!(view instanceof ImageView))
            return null;

        final Drawable drawable = ((ImageView) view).getDrawable();
        return (drawable instanceof BitmapDrawable) ? ((BitmapDrawable) drawable).getBitmap() : null;
    }

    private static String text(CharSequence charSequence) {
        return (charSequence == null) ? "" : charSequence.toString().trim();
    }
}
