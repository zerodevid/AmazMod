package com.edotassi.amazmod.notification.navigation;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import android.text.Spanned;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import org.tinylog.Logger;

import java.util.List;

import amazmod.com.transport.data.NavigationData;

/**
 * Extracts turn-by-turn data out of the ongoing Google Maps notification.
 *
 * Google Maps exposes no public API for the current navigation step, but its ongoing notification
 * is built from RemoteViews whose child views can be identified by their resource entry names.
 * The strategy is to rebuild those RemoteViews, inflate them into a real (never displayed)
 * ViewGroup, and read the values straight off the TextViews and the ImageView.
 *
 * Approach ported from GMapsNotification.kt of maisonsmd/esp32-google-maps (MIT).
 *
 * This is inherently coupled to Google Maps' notification layout: if Google renames those views
 * the parser returns an empty NavigationData and the caller must fall back to forwarding the
 * notification text as-is.
 */
public class GMapsNavigationParser {

    public static final String GMAPS_PACKAGE = "com.google.android.apps.maps";

    // Resource entry names inside the Maps notification layout
    private static final String VIEW_INSTRUCTION = "text";        // "Turn right onto Jl. Merdeka"
    private static final String VIEW_HEADER = "header_text";      // "23 min · 12 km · 20:45 ETA"
    private static final String VIEW_TITLE = "title";             // "450 m"
    private static final String VIEW_ICON = "right_icon";         // manoeuvre arrow

    // Maps separates ETE / distance / ETA with a middle dot
    private static final String HEADER_SEPARATOR = "·";
    private static final String ETA_SUFFIX = "ETA";

    private final Context context;
    private final Context mapsContext;
    private final Notification notification;
    private final NavigationData navigationData;

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
     *         could be extracted.
     */
    public NavigationData parse() {
        // The collapsed and expanded layouts do not always carry the same fields, so parse both
        // and let the second pass fill in whatever the first one missed.
        RemoteViews normalContent = getContentView(false);
        if (normalContent != null)
            parseRemoteView(normalContent);

        RemoteViews bestContent = getContentView(true);
        if (bestContent != null && bestContent != normalContent)
            parseRemoteView(bestContent);

        return navigationData;
    }

    private RemoteViews getContentView(boolean preferBig) {
        try {
            Notification.Builder builder = Notification.Builder.recoverBuilder(context, notification);

            if (preferBig) {
                RemoteViews big = builder.createBigContentView();
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
        LayoutInflater layoutInflater =
                (LayoutInflater) mapsContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        if (layoutInflater == null)
            return null;

        View inflated = layoutInflater.inflate(remoteViews.getLayoutId(), null);
        if (!(inflated instanceof ViewGroup))
            return null;

        ViewGroup viewGroup = (ViewGroup) inflated;
        // reapply() replays the RemoteViews actions onto the real views, which is what actually
        // puts the current navigation text into them
        remoteViews.reapply(mapsContext, viewGroup);

        return viewGroup;
    }

    private void parseRemoteView(RemoteViews remoteViews) {
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

        final TextView instructionText = (TextView) findChildByName(group, VIEW_INSTRUCTION, TextView.class);
        final TextView headerText = (TextView) findChildByName(group, VIEW_HEADER, TextView.class);
        final TextView titleText = (TextView) findChildByName(group, VIEW_TITLE, TextView.class);
        final ImageView rightIcon = (ImageView) findChildByName(group, VIEW_ICON, ImageView.class);

        parseHeader(headerText);
        parseDistanceToNext(titleText);
        parseInstruction(instructionText);
        parseIcon(rightIcon);
    }

    /** "23 min · 12 km · 20:45 ETA" -> ete / totalDistance / eta */
    private void parseHeader(TextView headerText) {
        if (headerText == null || headerText.getText() == null)
            return;

        final String[] parts = headerText.getText().toString().split(HEADER_SEPARATOR);
        if (parts.length != 3)
            return;

        navigationData.setEte(parts[0].trim());
        navigationData.setTotalDistance(parts[1].trim());

        String eta = parts[2].trim();
        if (eta.endsWith(ETA_SUFFIX))
            eta = eta.substring(0, eta.length() - ETA_SUFFIX.length()).trim();
        navigationData.setEta(eta);
    }

    /** "450 m" */
    private void parseDistanceToNext(TextView titleText) {
        if (titleText == null || titleText.getText() == null)
            return;

        final String distance = titleText.getText().toString().trim();
        if (!distance.isEmpty())
            navigationData.setDistanceToNext(distance);
    }

    /**
     * The instruction is a Spanned where the road name is BOLD. Everything before the first bold
     * run plus the bold run itself is the road; a second bold run (eg. "towards …") starts the
     * description.
     */
    private void parseInstruction(TextView instructionText) {
        if (instructionText == null || instructionText.getText() == null)
            return;

        final CharSequence text = instructionText.getText();

        if (!(text instanceof Spanned)) {
            // Not styled: Maps is showing a plain status such as "Rerouting…"
            final String plain = text.toString().trim();
            if (!plain.isEmpty()) {
                navigationData.setNextRoad(plain);
                navigationData.setRerouting(true);
            }
            return;
        }

        final List<SpanSplitter.Segment> segments =
                SpanSplitter.splitByStyleSpan((Spanned) text, Typeface.NORMAL, 2);

        if (segments.isEmpty())
            return;

        final StringBuilder road = new StringBuilder(segments.get(0).text);
        final StringBuilder description = new StringBuilder();

        // Look for a second "key" segment; everything from there on is the description
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
        navigationData.setRerouting(false);
    }

    private void parseIcon(ImageView rightIcon) {
        if (rightIcon == null)
            return;

        final Drawable drawable = rightIcon.getDrawable();
        if (!(drawable instanceof BitmapDrawable))
            return;

        final Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
        if (bitmap == null || bitmap.isRecycled())
            return;

        navigationData.setIcon(NavigationIconHelper.toPng(bitmap));
        navigationData.setIconHash(NavigationIconHelper.hash(navigationData.getIcon()));
    }

    /** Depth-first search for a view whose resource entry name matches, as seen by Maps' own resources. */
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

    private String getEntryName(View view) {
        try {
            if (view.getId() > 0)
                return mapsContext.getResources().getResourceEntryName(view.getId());
        } catch (Exception ignored) {
            // View ids that do not belong to Maps' resources simply have no entry name
        }
        return "";
    }
}
