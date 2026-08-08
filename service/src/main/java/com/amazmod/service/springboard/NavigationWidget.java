package com.amazmod.service.springboard;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.amazmod.service.MainService;
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
import clc.sliteplugin.flowboard.AbstractPlugin;
import clc.sliteplugin.flowboard.ISpringBoardHostStub;

/**
 * Springboard page mirroring the navigation screen, so the user can swipe to the current
 * instruction at any time instead of waiting for NavigationActivity to appear.
 */
public class NavigationWidget extends AbstractPlugin {

    private Context mContext;
    private View view;
    private boolean isActive = false;
    private ISpringBoardHostStub host = null;

    private ImageView iconImage;
    private TextView distanceText, roadText, idleText;
    private TextView remainingValue, durationValue, arrivalValue;
    private TextView clockText;
    private com.amazmod.service.ui.RouteProgressView progressBar;
    private ImageView compassImage;

    private NavigationCompass compass;

    private Handler clockHandler;
    private Runnable clockTicker;

    private static final long CLOCK_TICK_MS = 15000L;
    private LinearLayout content, idle;

    @Override
    public View getView(final Context paramContext) {
        this.mContext = paramContext;
        mContext.startService(new Intent(paramContext, MainService.class));

        Logger.debug("NavigationWidget getView");

        this.view = LayoutInflater.from(mContext).inflate(R.layout.navigation_widget, null);

        content = view.findViewById(R.id.navigation_widget_content);
        idle = view.findViewById(R.id.navigation_widget_idle);
        iconImage = view.findViewById(R.id.navigation_widget_icon);
        distanceText = view.findViewById(R.id.navigation_widget_distance);
        roadText = view.findViewById(R.id.navigation_widget_road);
        idleText = view.findViewById(R.id.navigation_widget_idle_text);

        remainingValue = view.findViewById(R.id.navigation_widget_remaining_value);
        durationValue = view.findViewById(R.id.navigation_widget_duration_value);
        arrivalValue = view.findViewById(R.id.navigation_widget_arrival_value);
        clockText = view.findViewById(R.id.navigation_widget_clock);
        progressBar = view.findViewById(R.id.navigation_widget_progress);
        compassImage = view.findViewById(R.id.navigation_widget_compass);

        // No status line here, so the widget relies on the needle's tint alone to show that the
        // compass is off; the full screen explains it in words
        compass = new NavigationCompass(mContext, compassImage);
        setupClock();

        final android.content.res.Resources res = mContext.getResources();
        idleText.setText(res.getString(R.string.navigation_idle));
        ((TextView) view.findViewById(R.id.navigation_widget_remaining_label))
                .setText(res.getString(R.string.navigation_label_remaining));
        ((TextView) view.findViewById(R.id.navigation_widget_duration_label))
                .setText(res.getString(R.string.navigation_label_duration));
        ((TextView) view.findViewById(R.id.navigation_widget_arrival_label))
                .setText(res.getString(R.string.navigation_label_arrival));

        if (!EventBus.getDefault().isRegistered(this))
            EventBus.getDefault().register(this);

        refresh();

        return this.view;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNavigationUpdate(NavigationUpdateEvent event) {
        refresh();
    }

    private void refresh() {
        if (view == null)
            return;

        final NavigationData data = NavigationStore.getCurrentData();

        if (data == null) {
            content.setVisibility(View.GONE);
            idle.setVisibility(View.VISIBLE);
            return;
        }

        content.setVisibility(View.VISIBLE);
        idle.setVisibility(View.GONE);

        final Bitmap icon = NavigationStore.getCurrentIcon();
        if (icon != null && !icon.isRecycled()) {
            iconImage.setImageBitmap(icon);
            iconImage.setVisibility(View.VISIBLE);
        } else {
            iconImage.setVisibility(View.GONE);
        }

        distanceText.setText(data.getDistanceToNext());
        roadText.setText(data.isRerouting() && data.getNextRoad().isEmpty()
                ? mContext.getResources().getString(R.string.navigation_rerouting)
                : data.getNextRoad());
        remainingValue.setText(NavigationFormat.orDash(data.getTotalDistance()));
        durationValue.setText(NavigationFormat.orDash(data.getEte()));
        arrivalValue.setText(NavigationFormat.orDash(data.getEta()));

        progressBar.setRoute(data.getSegmentLengths(), data.getSegmentColours(),
                data.getProgressPercent());
        progressBar.setVisibility(progressBar.hasRoute() ? View.VISIBLE : View.GONE);

        compass.setBearing(data.getBearing());
        updateClock();
    }

    private void setupClock() {
        clockHandler = new Handler(Looper.getMainLooper());
        clockTicker = new Runnable() {
            @Override
            public void run() {
                updateClock();
                clockHandler.postDelayed(this, CLOCK_TICK_MS);
            }
        };
    }

    private void updateClock() {
        if (clockText != null && mContext != null)
            clockText.setText(NavigationFormat.currentTime(mContext));
    }

    /*
     * Below there are standard widget methods
     */

    private void onShow() {
        if (this.view != null && !this.isActive)
            refresh();

        this.isActive = true;

        compass.setVisible(true);
        if (clockHandler != null && clockTicker != null)
            clockHandler.postDelayed(clockTicker, CLOCK_TICK_MS);
    }

    private void onHide() {
        this.isActive = false;

        // Nothing here is worth spending battery on once the page is out of sight
        compass.setVisible(false);
        if (clockHandler != null && clockTicker != null)
            clockHandler.removeCallbacks(clockTicker);
    }

    @Override
    public void onInactive(Bundle paramBundle) {
        super.onInactive(paramBundle);
        this.onHide();
    }

    @Override
    public void onPause() {
        super.onPause();
        this.onHide();
    }

    @Override
    public void onStop() {
        super.onStop();
        this.onHide();
    }

    @Override
    public void onActive(Bundle paramBundle) {
        super.onActive(paramBundle);
        this.onShow();
    }

    @Override
    public void onResume() {
        super.onResume();
        this.onShow();
    }

    @Override
    public void onDestroy() {
        if (EventBus.getDefault().isRegistered(this))
            EventBus.getDefault().unregister(this);

        if (compass != null)
            compass.release();
        if (clockHandler != null && clockTicker != null)
            clockHandler.removeCallbacks(clockTicker);

        super.onDestroy();
    }

    // The arrow is a vector drawable, so it has to be rasterised rather than cast to a BitmapDrawable
    @Override
    public Bitmap getWidgetIcon(Context paramContext) {
        final Drawable drawable = this.mContext.getResources().getDrawable(R.drawable.ic_navigation_white_24);

        if (drawable instanceof BitmapDrawable)
            return ((BitmapDrawable) drawable).getBitmap();

        final int width = Math.max(1, drawable.getIntrinsicWidth());
        final int height = Math.max(1, drawable.getIntrinsicHeight());

        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);

        return bitmap;
    }

    @Override
    public Intent getWidgetIntent() {
        return new Intent();
    }

    @Override
    public String getWidgetTitle(Context paramContext) {
        return this.mContext.getResources().getString(R.string.navigation_widget_name);
    }

    public ISpringBoardHostStub getHost() {
        return this.host;
    }

    @Override
    public void onBindHost(ISpringBoardHostStub paramISpringBoardHostStub) {
        this.host = paramISpringBoardHostStub;
    }
}
