package com.edotassi.amazmod.notification.navigation;

import android.graphics.Bitmap;

import org.tinylog.Logger;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;

/**
 * Turns the manoeuvre arrow of the Maps notification into something small enough to push over the
 * Bluetooth tunnel, and hashes it so the same arrow is only ever sent once.
 */
public class NavigationIconHelper {

    // The Verge screen is 360x360, so an arrow this size still looks sharp while staying a few kB
    private static final int ICON_SIZE = 96;
    private static final int PNG_QUALITY = 100;

    /** Scales the arrow down and encodes it as PNG, keeping its alpha channel. */
    public static byte[] toPng(Bitmap bitmap) {
        if (bitmap == null)
            return new byte[0];

        try {
            final Bitmap scaled = scale(bitmap);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out);

            if (scaled != bitmap)
                scaled.recycle();

            return out.toByteArray();

        } catch (Exception e) {
            Logger.error("NavigationIconHelper toPng exception: " + e.getMessage());
            return new byte[0];
        }
    }

    private static Bitmap scale(Bitmap bitmap) {
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();

        if (width <= 0 || height <= 0)
            return bitmap;

        if (width <= ICON_SIZE && height <= ICON_SIZE)
            return bitmap;

        final float ratio = Math.min((float) ICON_SIZE / width, (float) ICON_SIZE / height);
        final int targetWidth = Math.max(1, Math.round(width * ratio));
        final int targetHeight = Math.max(1, Math.round(height * ratio));

        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
    }

    /**
     * Short digest of the encoded icon. Used as a cache key on both sides so an arrow that the
     * watch already has is never re-sent.
     */
    public static String hash(byte[] data) {
        if (data == null || data.length == 0)
            return "";

        try {
            final MessageDigest digest = MessageDigest.getInstance("MD5");
            final byte[] messageDigest = digest.digest(data);

            final StringBuilder hex = new StringBuilder();
            for (byte b : messageDigest) {
                final String h = Integer.toHexString(0xFF & b);
                if (h.length() < 2)
                    hex.append('0');
                hex.append(h);
            }

            // The last 10 chars are plenty to tell a handful of manoeuvre arrows apart
            final String full = hex.toString();
            return full.substring(full.length() - 10);

        } catch (Exception e) {
            Logger.error("NavigationIconHelper hash exception: " + e.getMessage());
            return "";
        }
    }
}
