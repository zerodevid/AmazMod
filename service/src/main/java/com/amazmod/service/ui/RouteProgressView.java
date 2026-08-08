package com.amazmod.service.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * The route drawn as the phone draws it: stretches of colour for clear road, slow traffic and
 * standstill, with the part already covered dimmed and a marker for where you are.
 *
 * A plain percentage bar answers only "how far along am I". This answers "what is the road ahead
 * like", which is the question the colours on the phone are there to settle, and it does it without
 * needing to be read.
 */
public class RouteProgressView extends View {

    // Everything already behind the marker is dimmed rather than recoloured, so the traffic that
    // was there stays visible without competing with the road still to come
    private static final int TRAVELLED_ALPHA = 70;
    private static final float CORNER_RADIUS_DP = 3.5f;
    // A jam a few hundred metres long is a rounding error on a thousand-kilometre route and would
    // never reach a whole pixel. Given the choice between a jam drawn slightly too wide and one
    // nobody can see, too wide wins
    private static final float MIN_BUSY_WIDTH_DP = 2.5f;
    private static final float MARKER_RADIUS_DP = 3.5f;
    private static final int MARKER_COLOUR = 0xFFFFFFFF;
    private static final int EMPTY_COLOUR = 0xFF25272C;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int[] lengths = new int[0];
    private int[] colours = new int[0];
    private int progressPercent = -1;

    private final Path clip = new Path();
    private final RectF bounds = new RectF();

    private float cornerRadius;
    private float markerRadius;
    private float minBusyWidth;

    public RouteProgressView(Context context) {
        super(context);
        init();
    }

    public RouteProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RouteProgressView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        final float density = getResources().getDisplayMetrics().density;
        cornerRadius = CORNER_RADIUS_DP * density;
        markerRadius = MARKER_RADIUS_DP * density;
        minBusyWidth = MIN_BUSY_WIDTH_DP * density;
        paint.setStyle(Paint.Style.FILL);
    }

    /**
     * @param progressPercent how far along the route we are, 0-100, or -1 when unknown
     */
    public void setRoute(int[] lengths, int[] colours, int progressPercent) {
        this.lengths = (lengths == null) ? new int[0] : lengths;
        this.colours = (colours == null) ? new int[0] : colours;
        this.progressPercent = progressPercent;
        invalidate();
    }

    /** True when there is a route worth drawing, so the caller can hide an empty bar. */
    public boolean hasRoute() {
        return lengths.length > 0 && total() > 0;
    }

    /** Anything Maps does not paint as clear road or as already travelled. */
    private boolean isBusy(int colour) {
        final int rgb = colour & 0x00FFFFFF;
        return rgb != 0x00B0FF && rgb != 0x727272;
    }

    private long total() {
        long sum = 0;
        for (int length : lengths)
            sum += Math.max(0, length);
        return sum;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final float width = getWidth();
        final float height = getHeight();
        if (width <= 0 || height <= 0)
            return;

        final long total = total();

        if (total <= 0) {
            // Nothing known about the road: an empty track rather than a misleading full bar
            paint.setColor(EMPTY_COLOUR);
            rect.set(0, 0, width, height);
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint);
            return;
        }

        // Rounding every stretch separately put a notch at each boundary and the bar read as a row
        // of separate pills. The rounding belongs to the bar, not to its parts: clip once to a
        // rounded outline and fill it with plain rectangles.
        bounds.set(0, 0, width, height);
        clip.reset();
        clip.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW);

        final int saved = canvas.save();
        canvas.clipPath(clip);

        final float travelledX = (progressPercent < 0)
                ? -1f
                : width * Math.min(100, Math.max(0, progressPercent)) / 100f;

        float x = 0;
        for (int i = 0; i < lengths.length && i < colours.length; i++) {
            float span = width * Math.max(0, lengths[i]) / (float) total;
            final int colour = colours[i];

            if (span <= 0)
                continue;

            // Widen a congested stretch that would otherwise vanish, at the cost of pushing what
            // follows slightly along
            if (span < minBusyWidth && isBusy(colour))
                span = minBusyWidth;

            final boolean behind = (travelledX >= 0) && (x + span <= travelledX);

            paint.setColor(colour);
            paint.setAlpha(behind ? TRAVELLED_ALPHA : 255);
            canvas.drawRect(x, 0, x + span, height, paint);

            // The stretch the marker sits inside is dimmed only up to the marker, so the boundary
            // lands where we actually are rather than at the nearest stretch edge
            if (travelledX > x && travelledX < x + span) {
                paint.setAlpha(TRAVELLED_ALPHA);
                canvas.drawRect(x, 0, travelledX, height, paint);
            }

            x += span;
        }

        canvas.restoreToCount(saved);

        if (travelledX >= 0) {
            // Kept a full radius away from either end. At the start of a trip the marker sits at
            // x=0 and half of it falls outside the view, which draws as a half circle and reads as
            // a rendering fault rather than as "you are at the beginning".
            final float markerX = Math.max(markerRadius, Math.min(width - markerRadius, travelledX));

            paint.setColor(MARKER_COLOUR);
            paint.setAlpha(255);
            canvas.drawCircle(markerX, height / 2f, markerRadius, paint);
        }
    }
}
