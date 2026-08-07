package com.amazmod.service.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
import com.amazmod.service.support.NavigationStore;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.tinylog.Logger;

import android.text.format.DateFormat;

import java.util.Date;

import amazmod.com.transport.data.NavigationData;

import static android.content.Context.SENSOR_SERVICE;

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
    // Fraction of each new reading folded in; low enough that the needle stops shivering
    private static final float COMPASS_SMOOTHING = 0.15f;

    private ImageView iconImage;
    private FrameLayout iconHolder;
    private ProgressBar progressBar;
    private TextView clockText;
    private ImageView compassImage;

    private SensorManager sensorManager;
    private SensorEventListener compassListener;
    // Where Maps told us to head, degrees from north, or -1 when it named no direction
    private int targetBearing = -1;
    // Smoothed heading of the watch itself
    private float heading = Float.NaN;
    private boolean compassRunning = false;
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

        setupCompass();
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

        startCompass();
    }

    @Override
    protected void onPause() {
        if (EventBus.getDefault().isRegistered(this))
            EventBus.getDefault().unregister(this);

        if (staleHandler != null)
            staleHandler.removeCallbacks(staleCheck);

        stopCompass();

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

        targetBearing = data.getBearing();
        updateCompass();

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
        statusText.setVisibility(View.GONE);
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
        remainingValue.setText(orDash(data.getTotalDistance()));
        durationValue.setText(orDash(data.getEte()));
        arrivalValue.setText(orDash(data.getEta()));

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
     * The compass only earns its place when Maps names a direction rather than a manoeuvre - at the
     * start of a trip, or after losing the route - which is exactly when knowing which way to face
     * is worth something. The rest of the time it stays out of the way.
     */
    private void setupCompass() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager == null) {
            Logger.warn("NavigationActivity no SensorManager, compass unavailable");
            return;
        }

        compassListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                // Readings jitter by several degrees; without smoothing the needle never settles
                final float azimuth = event.values[0];
                heading = Float.isNaN(heading) ? azimuth : smooth(heading, azimuth);
                updateCompass();
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
    }

    /** Circular low-pass: averages across 0/360 without the needle flipping at north. */
    private static float smooth(float previous, float next) {
        float delta = next - previous;
        while (delta > 180f) delta -= 360f;
        while (delta < -180f) delta += 360f;

        float result = previous + delta * COMPASS_SMOOTHING;
        while (result < 0f) result += 360f;
        while (result >= 360f) result -= 360f;

        return result;
    }

    /**
     * The magnetometer is only registered while a bearing is actually on screen. Maps names a
     * direction for a small part of a trip, so leaving the sensor running for the whole journey
     * would spend battery on a needle nobody is being shown - and this screen may well be lit for
     * hours.
     */
    private void startCompass() {
        if (sensorManager == null || compassListener == null || targetBearing < 0 || compassRunning)
            return;

        compassRunning = true;

        // The Huami orientation sensor reports azimuth directly and is the one alive on this watch
        final Sensor orientation = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);
        if (orientation == null) {
            Logger.warn("NavigationActivity no orientation sensor, compass unavailable");
            return;
        }

        sensorManager.registerListener(compassListener, orientation, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void stopCompass() {
        if (!compassRunning)
            return;

        compassRunning = false;
        if (sensorManager != null && compassListener != null)
            sensorManager.unregisterListener(compassListener);
    }

    private void updateCompass() {
        if (compassImage == null)
            return;

        if (targetBearing < 0) {
            compassImage.setVisibility(View.GONE);
            stopCompass();
            return;
        }

        startCompass();

        if (Float.isNaN(heading)) {
            // Registered but no reading yet; showing an unrotated needle would point at nothing
            compassImage.setVisibility(View.GONE);
            return;
        }

        // Rotating by the difference points the needle at the target no matter which way the
        // wrist is turned: face the right way and it points straight up
        compassImage.setRotation(targetBearing - heading);
        compassImage.setVisibility(View.VISIBLE);
    }

    /**
     * The watch face is not visible while this screen is up, so it shows the time itself. Refreshed
     * on every navigation update and on the stale tick, which is far more often than a minute.
     */
    private void updateClock() {
        clockText.setText(DateFormat.getTimeFormat(this).format(new Date()));
    }

    private String orDash(String value) {
        return value.isEmpty() ? "\u2014" : value;
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
