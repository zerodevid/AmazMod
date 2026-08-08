package com.amazmod.service.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.amazmod.service.R;
import com.amazmod.service.events.NavigationUpdateEvent;
import com.amazmod.service.support.NavigationCompass;
import com.amazmod.service.support.NavigationFormat;
import com.amazmod.service.support.NavigationStore;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.tinylog.Logger;

import amazmod.com.transport.data.NavigationData;

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
    private FrameLayout iconHolder;
    private ProgressBar progressBar;
    private TextView clockText;
    private ImageView compassImage;

    private NavigationCompass compass;
    private boolean calibrationNeeded = false;
    private boolean phoneSilent = false;
    private TextView distanceText, roadText, roadDescriptionText, statusText;
    private TextView remainingValue, durationValue, arrivalValue;
    private TextView remainingLabel, durationLabel, arrivalLabel;

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
        iconHolder = findViewById(R.id.activity_navigation_icon_holder);
        progressBar = findViewById(R.id.activity_navigation_progress);
        clockText = findViewById(R.id.activity_navigation_clock);
        compassImage = findViewById(R.id.activity_navigation_compass);

        compass = new NavigationCompass(this, compassImage);
        compass.setCalibrationListener(new NavigationCompass.CalibrationListener() {
            @Override
            public void onCalibrationNeeded(boolean needed) {
                calibrationNeeded = needed;
                updateStatus();
            }
        });
        distanceText = findViewById(R.id.activity_navigation_distance);
        roadText = findViewById(R.id.activity_navigation_road);
        roadDescriptionText = findViewById(R.id.activity_navigation_road_description);
        statusText = findViewById(R.id.activity_navigation_status);

        remainingValue = findViewById(R.id.activity_navigation_remaining_value);
        durationValue = findViewById(R.id.activity_navigation_duration_value);
        arrivalValue = findViewById(R.id.activity_navigation_arrival_value);
        remainingLabel = findViewById(R.id.activity_navigation_remaining_label);
        durationLabel = findViewById(R.id.activity_navigation_duration_label);
        arrivalLabel = findViewById(R.id.activity_navigation_arrival_label);

        remainingLabel.setText(R.string.navigation_label_remaining);
        durationLabel.setText(R.string.navigation_label_duration);
        arrivalLabel.setText(R.string.navigation_label_arrival);

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

        compass.setVisible(true);
    }

    @Override
    protected void onPause() {
        if (EventBus.getDefault().isRegistered(this))
            EventBus.getDefault().unregister(this);

        if (staleHandler != null)
            staleHandler.removeCallbacks(staleCheck);

        compass.setVisible(false);

        super.onPause();
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

        applyKeepScreenOn(data);
        updateClock();

        compass.setBearing(data.getBearing());

        // Maps is recalculating: it gives us a status line instead of a real instruction
        if (data.isRerouting()) {
            iconHolder.setVisibility(View.GONE);
            distanceText.setText("");
            roadText.setText(data.getNextRoad().isEmpty()
                    ? getString(R.string.navigation_rerouting) : data.getNextRoad());
            roadDescriptionText.setVisibility(View.GONE);
            showTripFigures(data);
            statusText.setVisibility(View.GONE);
            return;
        }

        final Bitmap icon = NavigationStore.getCurrentIcon();
        if (icon != null && !icon.isRecycled()) {
            iconImage.setImageBitmap(icon);
            iconHolder.setVisibility(View.VISIBLE);
        } else {
            iconHolder.setVisibility(View.GONE);
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

        showTripFigures(data);
        updateStatus();
    }

    /**
     * One line, two possible things to say. Losing the phone is reported first: a compass that
     * needs waving about matters less than directions that have stopped arriving.
     */
    private void updateStatus() {
        if (phoneSilent) {
            statusText.setText(R.string.navigation_waiting);
            statusText.setVisibility(View.VISIBLE);

        } else if (calibrationNeeded) {
            statusText.setText(R.string.navigation_calibrate);
            statusText.setVisibility(View.VISIBLE);

        } else {
            statusText.setVisibility(View.GONE);
        }
    }

    /**
     * Holding the display on for a whole trip is the single most expensive thing the watch can do,
     * so it follows the phone's setting rather than being decided here. FLAG_KEEP_SCREEN_ON is used
     * instead of a wake lock because it is scoped to this window: the moment the navigation screen
     * goes away the display returns to normal, with nothing to leak.
     */
    private void applyKeepScreenOn(NavigationData data) {
        if (data.isKeepScreenOn())
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /**
     * Fills the three trip figures. A field Maps did not provide shows an em dash rather than
     * collapsing, so the row keeps its shape and a missing value is obvious instead of silent.
     */
    private void showTripFigures(NavigationData data) {
        remainingValue.setText(NavigationFormat.orDash(data.getTotalDistance()));
        durationValue.setText(NavigationFormat.orDash(data.getEte()));
        arrivalValue.setText(NavigationFormat.orDash(data.getEta()));

        // Hidden rather than shown empty: a bar stuck at zero would read as "no progress made"
        final int percent = data.getProgressPercent();
        if (percent < 0) {
            progressBar.setVisibility(View.GONE);
        } else {
            progressBar.setProgress(Math.min(100, percent));
            progressBar.setVisibility(View.VISIBLE);
        }
    }

    /**
     * The watch face is not visible while this screen is up, so it shows the time itself. Refreshed
     * on every navigation update and on the stale tick, which is far more often than a minute.
     */
    private void updateClock() {
        clockText.setText(NavigationFormat.currentTime(this));
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
                updateClock();

                final long silence = System.currentTimeMillis() - NavigationStore.getLastUpdate();
                statusText.setVisibility(silence > SILENCE_WARNING ? View.VISIBLE : View.GONE);
                statusText.setText(R.string.navigation_waiting);

                staleHandler.postDelayed(this, STALE_CHECK_INTERVAL);
            }
        };
    }
}
