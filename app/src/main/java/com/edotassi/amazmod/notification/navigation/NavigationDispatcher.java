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
    private NavigationData pendingData;
    private byte[] pendingIcon = new byte[0];
    private Runnable flushTask;
    private final Handler heartbeat = new Handler(Looper.getMainLooper());
    private Runnable heartbeatTask;

    // When Maps last said anything, as opposed to when we last spoke to the watch
    private long lastNotificationTime = 0;

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

        // The newest reading always wins. Throttling is about how often we may transmit, not about
        // which data is current, and dropping a fresh reading here was leaving the watch showing
        // whatever happened to arrive first - typically "Rerouting" - for as long as Maps then
        // stayed quiet.
        pendingData = navigationData;
        pendingIcon = navigationData.getIcon();
        lastNotificationTime = System.currentTimeMillis();
        navigating = true;
        startHeartbeat();

        flush(System.currentTimeMillis());
        return true;
    }

    /**
     * Transmits the newest reading if the throttle allows, and otherwise arranges to transmit it as
     * soon as it does. Nothing is discarded: a reading that cannot go now goes shortly.
     */
    private void flush(long now) {
        if (pendingData == null)
            return;

        final long sinceLast = now - lastSentTime;
        final String signature = pendingData.getSignature();
        final boolean changed = !signature.equals(lastSignature);

        // Unchanged data still goes out periodically, so a packet lost while the tunnel was asleep
        // does not leave the watch stranded
        if (!changed && sinceLast < RESEND_INTERVAL)
            return;

        if (sinceLast < Constants.NAVIGATION_MIN_INTERVAL) {
            scheduleFlush(Constants.NAVIGATION_MIN_INTERVAL - sinceLast);
            return;
        }

        // Only pay for the arrow bitmap when the watch may not have it
        final String iconHash = pendingData.getIconHash();
        final boolean iconIsFresh = !iconHash.isEmpty()
                && sentIcons.containsKey(iconHash)
                && (now - sentIcons.get(iconHash)) < ICON_REFRESH_INTERVAL;
        pendingData.setIcon(iconIsFresh ? new byte[0] : pendingIcon);

        // Vibrate and wake the screen when the manoeuvre itself changes, not on every distance tick
        final boolean roadChanged = !pendingData.getNextRoad().equals(lastRoad);
        pendingData.setVibration(roadChanged && Prefs.getBoolean(
                Constants.PREF_NAVIGATION_VIBRATE_ON_TURN,
                Constants.PREF_DEFAULT_NAVIGATION_VIBRATE_ON_TURN) ? 150 : 0);
        pendingData.setScreenOn(roadChanged && Prefs.getBoolean(
                Constants.PREF_NAVIGATION_SCREEN_ON, Constants.PREF_DEFAULT_NAVIGATION_SCREEN_ON));

        // Sent on every packet, not just on a turn: the watch needs to know the current wish even
        // if it opened the navigation screen midway through a trip
        pendingData.setKeepScreenOn(Prefs.getBoolean(Constants.PREF_NAVIGATION_KEEP_SCREEN_ON,
                Constants.PREF_DEFAULT_NAVIGATION_KEEP_SCREEN_ON));

        // The watch extras are decided here rather than on the watch so every navigation setting
        // lives in one place instead of being split across two devices
        if (!Prefs.getBoolean(Constants.PREF_NAVIGATION_TRAFFIC,
                Constants.PREF_DEFAULT_NAVIGATION_TRAFFIC))
            pendingData.setTrafficAhead("");

        pendingData.setShowHeartRate(Prefs.getBoolean(Constants.PREF_NAVIGATION_HEART_RATE,
                Constants.PREF_DEFAULT_NAVIGATION_HEART_RATE));
        pendingData.setAutoBrightness(Prefs.getBoolean(Constants.PREF_NAVIGATION_AUTO_BRIGHTNESS,
                Constants.PREF_DEFAULT_NAVIGATION_AUTO_BRIGHTNESS));

        final boolean delivered = send(pendingData, now);
        lastSentTime = now;

        if (delivered) {
            lastSignature = signature;
            lastRoad = pendingData.getNextRoad();
        }
        // Otherwise the signature is left alone, so the next attempt is not mistaken for a
        // duplicate of one that never arrived
    }

    private void scheduleFlush(long delay) {
        if (flushTask != null)
            return;

        flushTask = new Runnable() {
            @Override
            public void run() {
                flushTask = null;
                if (navigating)
                    flush(System.currentTimeMillis());
            }
        };

        heartbeat.postDelayed(flushTask, Math.max(0, delay));
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
                if (!navigating || pendingData == null) {
                    heartbeatTask = null;
                    return;
                }

                final long now = System.currentTimeMillis();

                // A trip normally ends when Maps drops its notification, but that signal can be
                // missed - the listener rebinding, this process being restarted mid-trip, Maps
                // being force stopped. Without a second way out the heartbeat would go on pushing
                // stale directions to the watch for as long as the phone stayed awake.
                if ((now - lastNotificationTime) > Constants.NAVIGATION_IDLE_TIMEOUT) {
                    Logger.warn("NavigationDispatcher no word from Maps for {} ms, ending trip",
                            now - lastNotificationTime);
                    stopNavigation();
                    return;
                }
                // flush() already knows when unchanged data is due and how to decide about the
                // arrow, so the beat is only a reason to ask
                flush(now);

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
        if (flushTask != null) {
            heartbeat.removeCallbacks(flushTask);
            flushTask = null;
        }
    }

    /** Tells the watch that navigation ended, so it can close the navigation screen. */
    public void stopNavigation() {
        if (!navigating)
            return;

        Logger.debug("NavigationDispatcher navigation stopped");

        TransportService.sendWithTransporterAmazMod(Transport.NAVIGATION_STOP, new DataBundle());

        stopHeartbeat();
        pendingData = null;
        pendingIcon = new byte[0];
        lastNotificationTime = 0;

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
