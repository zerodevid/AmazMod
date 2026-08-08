package com.amazmod.service.support;

import android.content.Context;
import android.text.format.DateFormat;

import java.util.Date;

/**
 * The small pieces of formatting both navigation views need, kept in one place so they cannot drift
 * apart.
 */
public class NavigationFormat {

    /** An em dash rather than nothing, so a missing value is visibly missing and the row keeps its shape. */
    public static String orDash(String value) {
        return (value == null || value.isEmpty()) ? "—" : value;
    }

    /** Follows the watch's own 12/24 hour setting rather than imposing a format. */
    public static String currentTime(Context context) {
        return DateFormat.getTimeFormat(context).format(new Date());
    }
}
