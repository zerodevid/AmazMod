package com.edotassi.amazmod.notification.navigation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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

    /**
     * How long the same unchanged data may go unsent.
     *
     * Skipping identical packets assumes the last one arrived, and nothing here ever confirms that:
     * the transport fires and forgets. A packet sent while the tunnel was still asleep is simply
     * lost, and because the data has not changed since, every later packet is skipped as a
     * duplicate - so the watch can sit empty long after the phone started navigating. Repeating
     * unchanged data occasionally costs almost nothing and makes the watch catch up on its own.
     */
    private static final long RESEND_INTERVAL = 5000L;

    // Arrows already delivered to the watch this session, with when they were last sent
    private static final int ICON_CACHE_SIZE = 24;
    private final Map<String, Long> sentIcons = new LinkedHashMap<>();

    // Last data built, replayed by the heartbeat so the watch hears from us even when Maps is quiet.
    // The arrow is kept beside it rather than inside it: stripping the icon for one send must not
    // destroy it, or once the watch's cache expires there would be nothing left to resend.
    private NavigationData lastData;
    private byte[] lastIcon = new byte[0];
    private final Handler heartbeat = new Handler(Looper.getMainLooper());
    private Runnable heartbeatTask;

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

        if (signature.equals(lastSignature) && sinceLast < RESEND_INTERVAL) {
            //Logger.debug("NavigationDispatcher unchanged, skipping");
            return true;
        }

        if (sinceLast < Constants.NAVIGATION_MIN_INTERVAL) {
            //Logger.debug("NavigationDispatcher throttled ({} ms since last)", sinceLast);
            return true;
        }

        // Only pay for the arrow bitmap when the watch may not have it
        final String iconHash = navigationData.getIconHash();
        final byte[] fullIcon = navigationData.getIcon();
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

        final boolean delivered = send(navigationData, now);

        lastSentTime = now;
        lastData = navigationData;
        lastIcon = fullIcon;
        navigating = true;
        startHeartbeat();

        if (delivered) {
            lastSignature = signature;
            lastRoad = navigationData.getNextRoad();
        }
        // Otherwise the signature is left alone, so the next packet retries instead of being
        // mistaken for a duplicate of one that never arrived

        return true;
    }

    /**
     * @return whether the tunnel was up. The send itself is fire and forget, so this is the closest
     *         thing to a delivery result the transport gives us.
     */
    private boolean send(NavigationData navigationData, long now) {
        final DataBundle dataBundle = navigationData.toDataBundle(new DataBundle());
        TransportService.sendWithTransporterAmazMod(Transport.NAVIGATION_DATA, dataBundle);

        final boolean delivered = TransportService.isTransporterAmazModConnected();
        Logger.debug("NavigationDispatcher sent {} (tunnel up: {})", navigationData, delivered);

        final String iconHash = navigationData.getIconHash();
        if (delivered && !iconHash.isEmpty() && navigationData.getIcon().length > 0)
            rememberIcon(iconHash, now);

        return delivered;
    }

    /**
     * Repeats the last data on a timer for as long as a trip is running.
     *
     * Everything here is driven by Maps posting a notification, and Maps posts one only when
     * something it shows changes. Standing at a light or on a long straight it can stay quiet for
     * a minute at a time, and the watch - which cannot tell a quiet phone from a disconnected one -
     * gives up and says it is waiting. A heartbeat keeps that from happening and doubles as the
     * retry for a first packet that went out before the tunnel was awake.
     */
    private void startHeartbeat() {
        if (heartbeatTask != null)
            return;

        heartbeatTask = new Runnable() {
            @Override
            public void run() {
                if (!navigating || lastData == null) {
                    heartbeatTask = null;
                    return;
                }

                final long now = System.currentTimeMillis();
                if ((now - lastSentTime) >= RESEND_INTERVAL) {
                    // The arrow may have expired from the watch's cache since the last beat, so the
                    // decision is made fresh each time from the copy kept aside
                    final String iconHash = lastData.getIconHash();
                    final boolean iconIsFresh = !iconHash.isEmpty()
                            && sentIcons.containsKey(iconHash)
                            && (now - sentIcons.get(iconHash)) < ICON_REFRESH_INTERVAL;

                    lastData.setIcon(iconIsFresh ? new byte[0] : lastIcon);

                    send(lastData, now);
                    lastSentTime = now;
                }

                heartbeat.postDelayed(this, RESEND_INTERVAL);
            }
        };

        heartbeat.postDelayed(heartbeatTask, RESEND_INTERVAL);
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeat.removeCallbacks(heartbeatTask);
            heartbeatTask = null;
        }
    }

    /** Tells the watch that navigation ended, so it can close the navigation screen. */
    public void stopNavigation() {
        if (!navigating)
            return;

        Logger.debug("NavigationDispatcher navigation stopped");

        TransportService.sendWithTransporterAmazMod(Transport.NAVIGATION_STOP, new DataBundle());

        stopHeartbeat();
        lastData = null;
        lastIcon = new byte[0];

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
