package com.edotassi.amazmod.notification.navigation;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

import org.tinylog.Logger;

/**
 * Current speed, read from the phone's own GPS while a trip is running.
 *
 * Maps already has the GPS awake during navigation, so listening alongside it costs far less than
 * turning a receiver on from cold - which is why this is worth doing on the phone rather than
 * waking the watch's own GPS for the same number.
 */
public class SpeedProvider implements LocationListener {

    // Fast enough to keep up with acceleration, slow enough not to matter next to what Maps is
    // already doing with the same receiver
    private static final long MIN_INTERVAL_MS = 1000L;
    private static final float MIN_DISTANCE_M = 0f;

    // Below walking pace GPS speed is mostly noise, and a wobbling number is worse than none
    private static final float NOISE_FLOOR_MPS = 1.0f;

    private LocationManager locationManager;
    private boolean running = false;
    private float speedMps = -1f;

    public void start(Context context) {
        if (running)
            return;

        // checkSelfPermission rather than the support library version: minSdk is already 23, so it
        // is available, and this class stays free of AndroidX
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Logger.warn("SpeedProvider no location permission, speed unavailable");
            return;
        }

        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            Logger.error("SpeedProvider no LocationManager");
            return;
        }

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    MIN_INTERVAL_MS, MIN_DISTANCE_M, this);
            running = true;
            Logger.debug("SpeedProvider started");

        } catch (Exception e) {
            Logger.error("SpeedProvider could not start: " + e.getMessage());
        }
    }

    public void stop() {
        if (!running)
            return;

        running = false;
        speedMps = -1f;

        try {
            locationManager.removeUpdates(this);
            Logger.debug("SpeedProvider stopped");
        } catch (Exception e) {
            Logger.error("SpeedProvider could not stop: " + e.getMessage());
        }
    }

    /**
     * @return speed in km/h rounded to a whole number, or -1 when there is nothing trustworthy to
     *         report
     */
    public int getSpeedKmh() {
        if (speedMps < 0)
            return -1;

        return Math.round(speedMps * 3.6f);
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null || !location.hasSpeed())
            return;

        final float speed = location.getSpeed();
        speedMps = (speed < NOISE_FLOOR_MPS) ? 0f : speed;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
        speedMps = -1f;
    }
}
