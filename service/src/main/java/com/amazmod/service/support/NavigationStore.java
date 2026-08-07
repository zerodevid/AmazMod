package com.amazmod.service.support;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.collection.ArrayMap;

import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.List;

import amazmod.com.transport.Constants;
import amazmod.com.transport.data.NavigationData;

/**
 * Holds the navigation state currently pushed by the phone, so the navigation screen and the
 * springboard widget always show the same thing and survive being reopened.
 *
 * Manoeuvre arrows are cached by hash: the phone only sends the bitmap the first time it sees an
 * arrow and afterwards sends the hash alone, so this cache is what makes the icon still appear.
 */
public class NavigationStore {

    private static final int ICON_CACHE_SIZE = 24;

    private static NavigationData currentData = null;
    private static long lastUpdate = 0;

    private static final ArrayMap<String, Bitmap> iconCache = new ArrayMap<>();
    private static final List<String> iconOrder = new ArrayList<>();

    public static synchronized void update(NavigationData navigationData) {
        if (navigationData == null)
            return;

        cacheIcon(navigationData);

        currentData = navigationData;
        lastUpdate = System.currentTimeMillis();
    }

    public static synchronized NavigationData getCurrentData() {
        return currentData;
    }

    /** Arrow for the current step, or null when it was never received. */
    public static synchronized Bitmap getCurrentIcon() {
        if (currentData == null)
            return null;

        final String hash = currentData.getIconHash();
        if (hash.isEmpty())
            return null;

        return iconCache.get(hash);
    }

    public static synchronized boolean isNavigating() {
        return currentData != null;
    }

    /**
     * True when the phone stopped sending updates without telling us navigation ended, eg. because
     * Bluetooth dropped. The navigation screen uses this to close itself instead of showing a
     * frozen instruction.
     */
    public static synchronized boolean isStale() {
        return currentData != null
                && (System.currentTimeMillis() - lastUpdate) > Constants.NAVIGATION_STALE_TIMEOUT;
    }

    public static synchronized long getLastUpdate() {
        return lastUpdate;
    }

    public static synchronized void clear() {
        currentData = null;
        lastUpdate = 0;
        // Arrows are kept: the next trip reuses the same handful of icons and the phone will not
        // resend them
    }

    private static void cacheIcon(NavigationData navigationData) {
        final String hash = navigationData.getIconHash();
        final byte[] icon = navigationData.getIcon();

        if (hash.isEmpty() || icon == null || icon.length == 0)
            return;

        if (iconCache.containsKey(hash))
            return;

        try {
            final Bitmap bitmap = BitmapFactory.decodeByteArray(icon, 0, icon.length);
            if (bitmap == null) {
                Logger.warn("NavigationStore could not decode icon {}", hash);
                return;
            }

            if (iconOrder.size() >= ICON_CACHE_SIZE) {
                final String oldest = iconOrder.remove(0);
                final Bitmap evicted = iconCache.remove(oldest);
                if (evicted != null && !evicted.isRecycled())
                    evicted.recycle();
            }

            iconCache.put(hash, bitmap);
            iconOrder.add(hash);

        } catch (Exception e) {
            Logger.error("NavigationStore cacheIcon exception: " + e.getMessage());
        }
    }
}
