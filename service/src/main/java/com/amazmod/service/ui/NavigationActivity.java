package com.amazmod.service.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.amazmod.service.Constants;
import com.amazmod.service.R;
import com.amazmod.service.events.NavigationUpdateEvent;
import com.amazmod.service.springboard.WidgetSettings;
import com.amazmod.service.support.NavigationStore;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.List;

import amazmod.com.transport.data.NavigationData;

import static android.content.Context.POWER_SERVICE;

/**
 * Full screen turn-by-turn display, fed by the Google Maps notification running on the phone.
 *
 * The screen is intentionally passive: it renders whatever NavigationStore currently holds and
 * refreshes on NavigationUpdateEvent. It closes itself when navigation ends on the phone or when
 * the phone stops sending updates altogether.
 */
public class NavigationActivity extends Activity {

    private static final long STALE_CHECK_INTERVAL = 10000L;
    private static final long SILENCE_WARNING = 20000L;

    private ImageView iconImage;
    private TextView distanceText, roadText, roadDescriptionText, summaryText, statusText;

    private PowerManager.WakeLock wakeLock;
    private Handler staleHandler;
    private Runnable staleCheck;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        setContentView(R.layout.activity_navigation);

        iconImage = findViewById(R.id.activity_navigation_icon);
        distanceText = findViewById(R.id.activity_navigation_distance);
        roadText = findViewById(R.id.activity_navigation_road);
        roadDescriptionText = findViewById(R.id.activity_navigation_road_description);
        summaryText = findViewById(R.id.activity_navigation_summary);
        statusText = findViewById(R.id.activity_navigation_status);

        setupKeepAwake();
        setupStaleCheck();

        render();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!EventBus.getDefault().isRegistered(this))
            EventBus.getDefault().register(this);

        render();

        if (staleHandler != null)
            staleHandler.postDelayed(staleCheck, STALE_CHECK_INTERVAL);
    }

    @Override
    protected void onPause() {
        if (EventBus.getDefault().isRegistered(this))
            EventBus.getDefault().unregister(this);

        if (staleHandler != null)
            staleHandler.removeCallbacks(staleCheck);

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        releaseKeepAwake();
        super.onDestroy();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNavigationUpdate(NavigationUpdateEvent event) {
        if (!event.isNavigating()) {
            Logger.debug("NavigationActivity navigation ended, closing");
            finish();
            return;
        }

        render();
    }

    private void render() {
        final NavigationData data = NavigationStore.getCurrentData();

        if (data == null) {
            finish();
            return;
        }

        // Maps is recalculating: it gives us a status line instead of a real instruction
        if (data.isRerouting()) {
            iconImage.setVisibility(View.GONE);
            distanceText.setText("");
            roadText.setText(data.getNextRoad().isEmpty()
                    ? getString(R.string.navigation_rerouting) : data.getNextRoad());
            roadDescriptionText.setVisibility(View.GONE);
            summaryText.setText(buildSummary(data));
            statusText.setVisibility(View.GONE);
            return;
        }

        final Bitmap icon = NavigationStore.getCurrentIcon();
        if (icon != null && !icon.isRecycled()) {
            iconImage.setImageBitmap(icon);
            iconImage.setVisibility(View.VISIBLE);
        } else {
            iconImage.setVisibility(View.GONE);
        }

        distanceText.setText(data.getDistanceToNext());
        roadText.setText(data.getNextRoad());

        final String description = data.getNextRoadDescription();
        if (description.isEmpty()) {
            roadDescriptionText.setVisibility(View.GONE);
        } else {
            roadDescriptionText.setText(description);
            roadDescriptionText.setVisibility(View.VISIBLE);
        }

        summaryText.setText(buildSummary(data));
        statusText.setVisibility(View.GONE);
    }

    /** "23 min · 12 km · 20:45", skipping whichever parts Maps did not provide. */
    private String buildSummary(NavigationData data) {
        final List<String> parts = new ArrayList<>();

        if (!data.getEte().isEmpty())
            parts.add(data.getEte());
        if (!data.getTotalDistance().isEmpty())
            parts.add(data.getTotalDistance());
        if (!data.getEta().isEmpty())
            parts.add(data.getEta());

        final StringBuilder summary = new StringBuilder();
        for (String part : parts) {
            if (summary.length() > 0)
                summary.append(" · ");
            summary.append(part);
        }

        return summary.toString();
    }

    /**
     * Keeping the screen on for a whole trip is the point of this screen, but it is also the most
     * expensive thing the watch can do, so it stays behind the same widget setting the launcher
     * uses for "keep awake".
     */
    private void setupKeepAwake() {
        final WidgetSettings widgetSettings = new WidgetSettings(Constants.TAG, this);
        widgetSettings.reload();
        if (widgetSettings.get(Constants.PREF_NAVIGATION_KEEP_SCREEN_ON, 0) != 1)
            return;

        final PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null) {
            Logger.error("NavigationActivity null powerManager");
            return;
        }

        wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK,
                "AmazMod:NavigationScreenOn");
        wakeLock.acquire(60 * 60 * 1000L /* 1 hour */);
    }

    private void releaseKeepAwake() {
        if (wakeLock != null && wakeLock.isHeld())
            wakeLock.release();
        wakeLock = null;
    }

    /**
     * If the phone goes away mid-trip nothing tells us navigation stopped, so a frozen instruction
     * would stay on screen indefinitely. Close after the store goes stale.
     */
    private void setupStaleCheck() {
        staleHandler = new Handler(Looper.getMainLooper());
        staleCheck = new Runnable() {
            @Override
            public void run() {
                if (NavigationStore.isStale()) {
                    Logger.debug("NavigationActivity no updates from phone, closing");
                    NavigationStore.clear();
                    finish();
                    return;
                }

                // Warn before giving up, so a brief Bluetooth hiccup does not look like fresh data
                final long silence = System.currentTimeMillis() - NavigationStore.getLastUpdate();
                statusText.setVisibility(silence > SILENCE_WARNING ? View.VISIBLE : View.GONE);
                statusText.setText(R.string.navigation_waiting);

                staleHandler.postDelayed(this, STALE_CHECK_INTERVAL);
            }
        };
    }
}
