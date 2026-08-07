package com.edotassi.amazmod.notification.navigation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.tinylog.Logger;

/**
 * TEMPORARY (test scaffolding): walks the phone along a straight line between two points so Google
 * Maps believes it is moving, and produces real navigation updates without anyone leaving the desk.
 *
 * This exists to answer a question the stationary tests could not: whether Maps publishes the
 * distance to the next manoeuvre once there is actual movement. Remove it, and its manifest entry
 * and permission, before this branch is merged.
 *
 * Requires AmazMod to be picked under Developer options -> Select mock location app.
 *
 *   adb shell am broadcast -a com.edotassi.amazmod.MOCK_ROUTE \
 *     --es from "-6.2088,106.8456" --es to "-6.1751,106.8650" --ei speed 40
 *
 *   adb shell am broadcast -a com.edotassi.amazmod.MOCK_ROUTE --es stop true
 */
public class MockRouteReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.edotassi.amazmod.MOCK_ROUTE";

    private static final long TICK_MS = 1000L;
    private static final int DEFAULT_SPEED_KMH = 40;
    // Metres per degree of latitude; good enough for the city-scale distances this is used over
    private static final double METRES_PER_DEGREE = 111320.0;

    private static Handler handler;
    private static Runnable ticker;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction()))
            return;

        if (intent.hasExtra("stop")) {
            stop(context);
            return;
        }

        final double[] from = parsePoint(intent.getStringExtra("from"));
        final double[] to = parsePoint(intent.getStringExtra("to"));

        if (from == null || to == null) {
            Logger.error("MockRouteReceiver needs from and to as \"lat,lon\"");
            return;
        }

        final int speedKmh = intent.getIntExtra("speed", DEFAULT_SPEED_KMH);
        start(context, from, to, Math.max(1, speedKmh));
    }

    private void start(final Context context, final double[] from, final double[] to, final int speedKmh) {
        final LocationManager manager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            Logger.error("MockRouteReceiver no LocationManager");
            return;
        }

        if (!installProvider(manager)) {
            Logger.error("MockRouteReceiver could not become the mock provider - is AmazMod "
                    + "selected under Developer options / Select mock location app?");
            return;
        }

        stopTicker();

        // Latitude degrees are the same length everywhere; longitude degrees shrink towards the
        // poles, so scale them by the cosine of the latitude before measuring
        final double latScale = METRES_PER_DEGREE;
        final double lonScale = METRES_PER_DEGREE * Math.cos(Math.toRadians(from[0]));

        final double dx = (to[1] - from[1]) * lonScale;
        final double dy = (to[0] - from[0]) * latScale;
        final double totalMetres = Math.sqrt(dx * dx + dy * dy);

        if (totalMetres < 1) {
            Logger.error("MockRouteReceiver from and to are the same place");
            return;
        }

        final float bearing = (float) ((Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360);
        final double metresPerTick = (speedKmh / 3.6) * (TICK_MS / 1000.0);

        Logger.error("MockRouteReceiver walking {} m at {} km/h, bearing {}",
                Math.round(totalMetres), speedKmh, Math.round(bearing));

        handler = new Handler(Looper.getMainLooper());
        ticker = new Runnable() {
            double travelled = 0;

            @Override
            public void run() {
                final double fraction = Math.min(1.0, travelled / totalMetres);
                final double lat = from[0] + (to[0] - from[0]) * fraction;
                final double lon = from[1] + (to[1] - from[1]) * fraction;

                publish(manager, lat, lon, bearing, (float) (speedKmh / 3.6));

                if (fraction >= 1.0) {
                    Logger.error("MockRouteReceiver arrived");
                    return;
                }

                travelled += metresPerTick;
                handler.postDelayed(this, TICK_MS);
            }
        };

        handler.post(ticker);
    }

    private boolean installProvider(LocationManager manager) {
        try {
            try {
                manager.removeTestProvider(LocationManager.GPS_PROVIDER);
            } catch (Exception ignored) {
                // Not registered yet, which is the normal case on the first run
            }

            manager.addTestProvider(LocationManager.GPS_PROVIDER,
                    false, false, false, false, true, true, true,
                    android.location.Criteria.POWER_LOW,
                    android.location.Criteria.ACCURACY_FINE);
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);

            return true;

        } catch (SecurityException e) {
            Logger.error("MockRouteReceiver denied mock location: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Logger.error("MockRouteReceiver installProvider failed: " + e.getMessage());
            return false;
        }
    }

    private void publish(LocationManager manager, double lat, double lon, float bearing, float speed) {
        try {
            final Location location = new Location(LocationManager.GPS_PROVIDER);
            location.setLatitude(lat);
            location.setLongitude(lon);
            location.setAltitude(10);
            location.setAccuracy(3f);
            location.setBearing(bearing);
            location.setSpeed(speed);
            location.setTime(System.currentTimeMillis());
            // Without this the framework rejects the fix outright
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                location.setBearingAccuracyDegrees(1f);
                location.setSpeedAccuracyMetersPerSecond(1f);
                location.setVerticalAccuracyMeters(1f);
            }

            manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location);

        } catch (Exception e) {
            Logger.error("MockRouteReceiver publish failed: " + e.getMessage());
            stopTicker();
        }
    }

    private void stop(Context context) {
        stopTicker();

        final LocationManager manager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null)
            return;

        try {
            manager.removeTestProvider(LocationManager.GPS_PROVIDER);
            Logger.error("MockRouteReceiver stopped, real location restored");
        } catch (Exception e) {
            Logger.error("MockRouteReceiver stop failed: " + e.getMessage());
        }
    }

    private static void stopTicker() {
        if (handler != null && ticker != null)
            handler.removeCallbacks(ticker);
    }

    private static double[] parsePoint(String raw) {
        if (raw == null)
            return null;

        final String[] parts = raw.trim().split(",");
        if (parts.length != 2)
            return null;

        try {
            return new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
