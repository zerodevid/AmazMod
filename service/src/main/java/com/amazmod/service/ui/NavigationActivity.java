package com.amazmod.service.ui;

import android.app.Activity;
import android.content.Intent;
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
    // Whether this screen is the one being looked at, which decides if closing may change what is
    private boolean inForeground = false;

    private TextView heartText, speedText;
    private SensorManager sensorManager;
    private SensorEventListener heartListener, lightListener;
    private boolean heartWanted = false, brightnessWanted = false;
    private String trafficAhead = "";

    // Below this the screen is dimmed to save power; above it is daylight and needs full output
    private static final float DAYLIGHT_LUX = 2000f;
    private static final float NIGHT_LUX = 10f;
    private static final float MIN_BRIGHTNESS = 0.15f;
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
        heartText = findViewById(R.id.activity_navigation_heart);
        speedText = findViewById(R.id.activity_navigation_speed);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        setupExtraSensors();

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
        inForeground = true;

        if (heartWanted)
            startSensor(heartListener, Sensor.TYPE_HEART_RATE);
        if (brightnessWanted)
            startSensor(lightListener, Sensor.TYPE_LIGHT);
    }

    @Override
    protected void onPause() {
        if (EventBus.getDefault().isRegistered(this))
            EventBus.getDefault().unregister(this);

        if (staleHandler != null)
            staleHandler.removeCallbacks(staleCheck);

        compass.setVisible(false);
        inForeground = false;

        // Neither sensor is worth running for a screen nobody is looking at
        stopSensor(heartListener);
        stopSensor(lightListener);

        super.onPause();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNavigationUpdate(NavigationUpdateEvent event) {
        if (!event.isNavigating()) {
            Logger.debug("NavigationActivity navigation ended, closing");
            finishToHome();
            return;
        }

        render();
    }

    private void render() {
        final NavigationData data = NavigationStore.getCurrentData();

        if (data == null) {
            finishToHome();
            return;
        }

        applyKeepScreenOn(data);
        updateClock();

        compass.setBearing(data.getBearing());

        trafficAhead = data.getTrafficAhead();
        applyExtras(data);

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
     * The heart rate sensor and the light sensor are both opt-in from the phone, because both cost
     * battery on a watch whose screen may already be held on for the whole trip.
     */
    private void setupExtraSensors() {
        if (sensorManager == null) {
            Logger.warn("NavigationActivity no SensorManager, extras unavailable");
            return;
        }

        heartListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                final int bpm = Math.round(event.values[0]);
                // The sensor reports 0 while it is still looking for a pulse
                if (bpm <= 0)
                    return;

                heartText.setText("\u2665 " + bpm);
                heartText.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };

        lightListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                applyBrightness(event.values[0]);
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
    }

    private void applyExtras(NavigationData data) {
        final String speed = data.getSpeed();
        speedText.setText(speed);
        speedText.setVisibility(speed.isEmpty() ? View.GONE : View.VISIBLE);

        setHeartRate(data.isShowHeartRate());
        setAutoBrightness(data.isAutoBrightness());
        updateStatus();
    }

    private void setHeartRate(boolean wanted) {
        if (wanted == heartWanted)
            return;

        heartWanted = wanted;

        if (!wanted) {
            stopSensor(heartListener);
            heartText.setVisibility(View.GONE);
            return;
        }

        if (inForeground)
            startSensor(heartListener, Sensor.TYPE_HEART_RATE);
    }

    private void setAutoBrightness(boolean wanted) {
        if (wanted == brightnessWanted)
            return;

        brightnessWanted = wanted;

        if (!wanted) {
            stopSensor(lightListener);
            // Back to whatever the system would have chosen
            final android.view.WindowManager.LayoutParams params = getWindow().getAttributes();
            params.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(params);
            return;
        }

        if (inForeground)
            startSensor(lightListener, Sensor.TYPE_LIGHT);
    }

    /**
     * Brightness is set on this window rather than in system settings, so it applies only while
     * navigation is showing and cannot be left behind if the screen closes unexpectedly.
     */
    private void applyBrightness(float lux) {
        final float span = DAYLIGHT_LUX - NIGHT_LUX;
        final float fraction = Math.max(0f, Math.min(1f, (lux - NIGHT_LUX) / span));
        final float brightness = MIN_BRIGHTNESS + fraction * (1f - MIN_BRIGHTNESS);

        final android.view.WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = brightness;
        getWindow().setAttributes(params);
    }

    private void startSensor(SensorEventListener listener, int type) {
        if (sensorManager == null || listener == null)
            return;

        final Sensor sensor = sensorManager.getDefaultSensor(type);
        if (sensor == null) {
            Logger.warn("NavigationActivity sensor type {} not present", type);
            return;
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI);
    }

    private void stopSensor(SensorEventListener listener) {
        if (sensorManager != null && listener != null)
            sensorManager.unregisterListener(listener);
    }

    /**
     * Closes and leaves the watch on its watch face rather than on whatever happened to be behind
     * this screen, which after a trip is usually a stale AmazMod page nobody asked for.
     *
     * Only when this screen is the one being looked at. Navigation can end while the user has
     * already swiped somewhere else, and yanking them home from that would be worse than the
     * problem being solved.
     */
    private void finishToHome() {
        if (inForeground) {
            try {
                final Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(home);
            } catch (Exception e) {
                Logger.error("NavigationActivity could not return home: " + e.getMessage());
            }
        }

        finish();
    }

    /**
     * One line, two possible things to say. Losing the phone is reported first: a compass that
     * needs waving about matters less than directions that have stopped arriving.
     */
    private void updateStatus() {
        if (phoneSilent) {
            statusText.setTextColor(getResources().getColor(R.color.colorRed));
            statusText.setText(R.string.navigation_waiting);
            statusText.setVisibility(View.VISIBLE);

        } else if (calibrationNeeded) {
            statusText.setTextColor(getResources().getColor(R.color.colorRed));
            statusText.setText(R.string.navigation_calibrate);
            statusText.setVisibility(View.VISIBLE);

        } else if (!trafficAhead.isEmpty()) {
            // Amber, the colour Maps itself uses for a jam
            statusText.setTextColor(0xFFFFA000);
            statusText.setText(getString(R.string.navigation_traffic_ahead, trafficAhead));
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
                    finishToHome();
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
