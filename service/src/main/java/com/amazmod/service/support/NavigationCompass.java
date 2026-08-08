package com.amazmod.service.support;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.graphics.PorterDuff;
import android.view.View;
import android.widget.ImageView;

import org.tinylog.Logger;

/**
 * The compass needle shared by the navigation screen and the springboard page.
 *
 * Both show the same needle and had grown their own copy of the sensor handling, the smoothing and
 * the rotation maths. Keeping one copy here means a fix to any of it reaches both, which the
 * duplicated version could not promise.
 */
public class NavigationCompass implements SensorEventListener {

    // Fraction of each new reading folded in; low enough that the needle stops shivering
    private static final float SMOOTHING = 0.15f;

    // colorRed, matching the rest of the watch UI
    private static final int UNCALIBRATED_TINT = 0xFFED5B61;

    private final Context context;
    private final ImageView needle;
    private final SensorManager sensorManager;

    // Where the phone told us to head, degrees from north, or -1 when it named no direction
    private int targetBearing = -1;
    // Smoothed heading of the watch itself
    private float heading = Float.NaN;
    private boolean running = false;
    private boolean pageVisible = false;

    // Android tells us how much it trusts the magnetometer; below MEDIUM the needle can be tens of
    // degrees out, which is worse than no needle because it looks just as confident
    private int accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
    private CalibrationListener calibrationListener;

    /** Told whenever the needle starts or stops being trustworthy, so the host can explain why. */
    public interface CalibrationListener {
        void onCalibrationNeeded(boolean needed);
    }

    public NavigationCompass(Context context, ImageView needle) {
        this.context = context;
        this.needle = needle;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);

        if (sensorManager == null)
            Logger.warn("NavigationCompass no SensorManager, compass unavailable");
    }

    /** Called with -1 whenever the instruction names no direction, which is most of a trip. */
    public void setBearing(int bearing) {
        this.targetBearing = bearing;
        refresh();
        notifyCalibration();
    }

    /** Follows the host's visibility: a page nobody is looking at keeps no sensor awake. */
    public void setVisible(boolean visible) {
        this.pageVisible = visible;
        refresh();
        notifyCalibration();
    }

    public void setCalibrationListener(CalibrationListener listener) {
        this.calibrationListener = listener;
    }

    /** True when the reading is not worth trusting and the user should wave the watch about. */
    public boolean needsCalibration() {
        return accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW;
    }

    public void release() {
        pageVisible = false;
        stop();
    }

    private void refresh() {
        if (needle == null)
            return;

        if (!pageVisible || targetBearing < 0) {
            needle.setVisibility(View.GONE);
            stop();
            return;
        }

        start();

        if (Float.isNaN(heading)) {
            // Registered but no reading yet; an unrotated needle would point at nothing
            needle.setVisibility(View.GONE);
            return;
        }

        // An untrustworthy needle is still shown rather than hidden - it is roughly right, and
        // hiding it would leave no clue that anything is wrong - but tinted so it never looks as
        // confident as a calibrated one
        if (needsCalibration())
            needle.setColorFilter(UNCALIBRATED_TINT, PorterDuff.Mode.SRC_IN);
        else
            needle.clearColorFilter();

        // Rotating by the difference points the needle at the target no matter which way the wrist
        // is turned: face the right way and it points straight up
        needle.setRotation(targetBearing - heading);
        needle.setVisibility(View.VISIBLE);
    }

    private void start() {
        if (running || sensorManager == null)
            return;

        // The Huami orientation sensor reports azimuth directly and is the one alive on this watch;
        // its rotation vector reports all zeros
        final Sensor orientation = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);
        if (orientation == null) {
            Logger.warn("NavigationCompass no orientation sensor, compass unavailable");
            return;
        }

        running = true;
        sensorManager.registerListener(this, orientation, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void stop() {
        if (!running)
            return;

        running = false;
        if (sensorManager != null)
            sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // Readings jitter by several degrees; without smoothing the needle never settles
        final float azimuth = event.values[0];
        heading = Float.isNaN(heading) ? azimuth : smooth(heading, azimuth);
        refresh();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (this.accuracy == accuracy)
            return;

        this.accuracy = accuracy;
        Logger.debug("NavigationCompass accuracy now {}", accuracy);

        refresh();
        notifyCalibration();
    }

    private void notifyCalibration() {
        if (calibrationListener != null)
            calibrationListener.onCalibrationNeeded(pageVisible && targetBearing >= 0 && needsCalibration());
    }

    /** Circular low-pass: averages across 0/360 without the needle flipping at north. */
    static float smooth(float previous, float next) {
        float delta = next - previous;
        while (delta > 180f) delta -= 360f;
        while (delta < -180f) delta += 360f;

        float result = previous + delta * SMOOTHING;
        while (result < 0f) result += 360f;
        while (result >= 360f) result -= 360f;

        return result;
    }
}
