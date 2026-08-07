package com.edotassi.amazmod.notification.navigation;

import android.content.Context;
import android.service.notification.StatusBarNotification;

import com.edotassi.amazmod.transport.TransportService;
import com.huami.watch.transport.DataBundle;
import com.pixplicity.easyprefs.library.Prefs;

import org.tinylog.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

import amazmod.com.transport.Constants;
import amazmod.com.transport.Transport;
import amazmod.com.transport.data.NavigationData;

/**
 * Decides what actually goes over the tunnel while Google Maps navigation is running.
 *
 * The Maps notification is updated several times per second, so sending every update would flood
 * the Bluetooth link and drain both batteries. Only genuine changes are forwarded, at most one
 * packet per NAVIGATION_MIN_INTERVAL, and each manoeuvre arrow travels only the first time it is
 * seen.
 */
public class NavigationDispatcher {

    /**
     * How long an arrow is assumed to still be cached on the watch.
     *
     * Sending each arrow once and then only its hash saves real bandwidth, but it quietly assumes
     * the watch keeps its cache forever. It does not: the watch cache lives in memory and is lost
     * whenever the watch service restarts, and the phone has no way to hear about that. Resending
     * periodically costs about a kilobyte a minute and makes a lost arrow heal itself instead of
     * staying blank for the rest of the trip.
     */
    private static final long ICON_REFRESH_INTERVAL = 60000L;

    // Arrows already delivered to the watch this session, with when they were last sent
    private static final int ICON_CACHE_SIZE = 24;
    private final Map<String, Long> sentIcons = new LinkedHashMap<>();

    private String lastSignature = "";
    private String lastRoad = "";
    private long lastSentTime = 0;
    private boolean navigating = false;

    /**
     * Parses and forwards one Maps notification.
     *
     * @return true when navigation data was handled, false when the caller should fall back to
     *         sending the notification as plain text.
     */
    public boolean handleMapsNotification(Context context, StatusBarNotification sbn) {

        if (!Prefs.getBoolean(Constants.PREF_ENABLE_NAVIGATION, Constants.PREF_DEFAULT_ENABLE_NAVIGATION)) {
            Logger.debug("NavigationDispatcher navigation disabled in settings");
            return false;
        }

        final NavigationData navigationData;
        try {
            navigationData = new GMapsNavigationParser(context, sbn).parse();
        } catch (Exception e) {
            Logger.error("NavigationDispatcher parse exception: " + e.getMessage());
            return false;
        }

        if (navigationData.isEmpty()) {
            Logger.warn("NavigationDispatcher parser found no navigation data, falling back");
            return false;
        }

        final String signature = navigationData.getSignature();
        final long now = System.currentTimeMillis();
        final long sinceLast = now - lastSentTime;

        if (signature.equals(lastSignature)) {
            //Logger.debug("NavigationDispatcher unchanged, skipping");
            return true;
        }

        if (sinceLast < Constants.NAVIGATION_MIN_INTERVAL) {
            //Logger.debug("NavigationDispatcher throttled ({} ms since last)", sinceLast);
            return true;
        }

        // Only pay for the arrow bitmap when the watch may not have it
        final String iconHash = navigationData.getIconHash();
        final boolean iconIsFresh = !iconHash.isEmpty()
                && sentIcons.containsKey(iconHash)
                && (now - sentIcons.get(iconHash)) < ICON_REFRESH_INTERVAL;
        if (iconIsFresh)
            navigationData.setIcon(new byte[0]);

        // Vibrate and wake the screen when the manoeuvre itself changes, not on every distance tick
        final boolean roadChanged = !navigationData.getNextRoad().equals(lastRoad);
        if (roadChanged && Prefs.getBoolean(Constants.PREF_NAVIGATION_VIBRATE_ON_TURN,
                Constants.PREF_DEFAULT_NAVIGATION_VIBRATE_ON_TURN)) {
            navigationData.setVibration(150);
        }
        navigationData.setScreenOn(roadChanged && Prefs.getBoolean(
                Constants.PREF_NAVIGATION_SCREEN_ON, Constants.PREF_DEFAULT_NAVIGATION_SCREEN_ON));

        // Sent on every packet, not just on a turn: the watch needs to know the current wish even
        // if it opened the navigation screen midway through a trip
        navigationData.setKeepScreenOn(Prefs.getBoolean(Constants.PREF_NAVIGATION_KEEP_SCREEN_ON,
                Constants.PREF_DEFAULT_NAVIGATION_KEEP_SCREEN_ON));

        final DataBundle dataBundle = navigationData.toDataBundle(new DataBundle());
        TransportService.sendWithTransporterAmazMod(Transport.NAVIGATION_DATA, dataBundle);

        Logger.debug("NavigationDispatcher sent {}", navigationData);

        if (!iconHash.isEmpty() && navigationData.getIcon().length > 0)
            rememberIcon(iconHash, now);

        lastSignature = signature;
        lastRoad = navigationData.getNextRoad();
        lastSentTime = now;
        navigating = true;

        return true;
    }

    /** Tells the watch that navigation ended, so it can close the navigation screen. */
    public void stopNavigation() {
        if (!navigating)
            return;

        Logger.debug("NavigationDispatcher navigation stopped");

        TransportService.sendWithTransporterAmazMod(Transport.NAVIGATION_STOP, new DataBundle());

        navigating = false;
        lastSignature = "";
        lastRoad = "";
        lastSentTime = 0;
        // A new trip starts with no assumptions about what the watch still holds
        sentIcons.clear();
    }

    public boolean isNavigating() {
        return navigating;
    }

    private void rememberIcon(String iconHash, long sentAt) {
        if (sentIcons.size() >= ICON_CACHE_SIZE && !sentIcons.containsKey(iconHash)) {
            final String oldest = sentIcons.keySet().iterator().next();
            sentIcons.remove(oldest);
        }
        sentIcons.put(iconHash, sentAt);
    }
}
