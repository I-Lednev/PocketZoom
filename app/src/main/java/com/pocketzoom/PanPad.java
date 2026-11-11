package com.pocketzoom;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Transparent "trackpad" in the bottom-right corner that pans the magnified
 * screen with a finger.
 *
 * <p>Android does not hand joystick axes (right stick, D-pad hats) to
 * accessibility services, and /dev/input is not readable without root, so the
 * physical stick cannot drive the magnifier. An overlay added by the service
 * can, however, receive touches: those touches are consumed here and never
 * reach the game below, which makes this a safe substitute.</p>
 *
 * <p>The overlay lives inside the magnified display, so its window would be
 * drawn and hit-tested in magnified space. {@link #sync()} therefore places
 * the window so that its magnified projection always lands in the corner, at
 * a constant on-screen size.</p>
 */
final class PanPad {
    private static final String TAG = "PocketZoom";

    /** Share of the screen the pad covers. */
    private static final float WIDTH_FRACTION = 0.34f;
    private static final float HEIGHT_FRACTION = 0.30f;
    private static final float MARGIN_DP = 8f;

    private final ZoomAccessibilityService service;
    private final WindowManager windowManager;
    private final float density;

    private PadView view;
    private WindowManager.LayoutParams params;
    private boolean attached;
    private boolean dragging;
    private float lastX;
    private float lastY;

    PanPad(ZoomAccessibilityService service) {
        this.service = service;
        this.windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        this.density = service.getResources().getDisplayMetrics().density;
    }

    /** Adds the pad to the screen. Safe to call repeatedly. */
    void show() {
        if (attached || windowManager == null) {
            return;
        }
        try {
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

            params = new WindowManager.LayoutParams(
                    Math.max(1, Math.round(240 * density)),
                    Math.max(1, Math.round(170 * density)),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    flags,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.setTitle("Pocket Zoom pan pad");

            view = new PadView();
            sync();
            windowManager.addView(view, params);
            attached = true;
            sync();
        } catch (Throwable t) {
            Log.w(TAG, "pan pad show failed", t);
            view = null;
            params = null;
            attached = false;
        }
    }

    /** Removes the pad. Safe to call when it is not shown. */
    void hide() {
        if (!attached || view == null) {
            return;
        }
        try {
            windowManager.removeViewImmediate(view);
        } catch (Throwable t) {
            Log.w(TAG, "pan pad hide failed", t);
        }
        view = null;
        params = null;
        attached = false;
        dragging = false;
    }

    /**
     * Keeps the pad's magnified projection in the bottom-right corner with a
     * constant on-screen size, whatever the current zoom and centre are.
     */
    void sync() {
        if (!attached || params == null || view == null) {
            return;
        }
        try {
            AccessibilityService.MagnificationController controller =
                    service.getMagnificationController();
            float scale = Math.max(1.01f, controller.getScale());
            float centerX = controller.getCenterX();
            float centerY = controller.getCenterY();

            DisplayMetrics metrics = service.screenMetrics();
            float width = metrics.widthPixels;
            float height = metrics.heightPixels;
            float margin = MARGIN_DP * density;

            // On-screen rectangle we want the pad to occupy.
            float screenW = width * WIDTH_FRACTION;
            float screenH = height * HEIGHT_FRACTION;
            float screenLeft = width - margin - screenW;
            float screenTop = height - margin - screenH;

            // The magnifier draws window coordinates at
            //   screen = (window - center) * scale + screenCentre,
            // so invert that to get the window frame which projects onto the
            // wanted rectangle.
            int windowW = Math.max(1, Math.round(screenW / scale));
            int windowH = Math.max(1, Math.round(screenH / scale));
            int x = Math.round(centerX + (screenLeft - width / 2f) / scale);
            int y = Math.round(centerY + (screenTop - height / 2f) / scale);

            if (params.width != windowW || params.height != windowH
                    || params.x != x || params.y != y) {
                params.width = windowW;
                params.height = windowH;
                params.x = x;
                params.y = y;
                windowManager.updateViewLayout(view, params);
            }
        } catch (Throwable t) {
            Log.w(TAG, "pan pad sync failed", t);
        }
    }

    private final class PadView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grip = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        PadView() {
            super(service);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.argb(18, 255, 255, 255));
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(Color.argb(70, 255, 255, 255));
            grip.setStyle(Paint.Style.STROKE);
            grip.setStrokeCap(Paint.Cap.ROUND);
            grip.setColor(Color.argb(110, 255, 255, 255));
            setAlpha(0.65f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float unit = Math.min(w, h);
            float inset = Math.max(1.5f, unit * 0.02f);
            float radius = unit * 0.14f;

            stroke.setStrokeWidth(Math.max(1f, unit * 0.008f));
            grip.setStrokeWidth(Math.max(1f, unit * 0.012f));

            rect.set(inset, inset, w - inset, h - inset);
            canvas.drawRoundRect(rect, radius, radius, fill);
            canvas.drawRoundRect(rect, radius, radius, stroke);

            // Four chevrons, so the pad reads as "move" without a label.
            float cx = w / 2f;
            float cy = h / 2f;
            float reach = unit * 0.22f;
            float gap = unit * 0.10f;
            float arm = unit * 0.075f;

            drawChevron(canvas, cx - gap, cy, -1f, 0f, reach, arm);
            drawChevron(canvas, cx + gap, cy, 1f, 0f, reach, arm);
            drawChevron(canvas, cx, cy - gap, 0f, -1f, reach, arm);
            drawChevron(canvas, cx, cy + gap, 0f, 1f, reach, arm);
        }

        private void drawChevron(Canvas canvas, float x, float y,
                                 float dirX, float dirY, float length, float arm) {
            float tipX = x + dirX * length;
            float tipY = y + dirY * length;
            canvas.drawLine(x, y, tipX, tipY, grip);
            canvas.drawLine(tipX, tipY,
                    tipX - dirX * arm - dirY * arm * 0.8f,
                    tipY - dirY * arm + dirX * arm * 0.8f, grip);
            canvas.drawLine(tipX, tipY,
                    tipX - dirX * arm + dirY * arm * 0.8f,
                    tipY - dirY * arm - dirX * arm * 0.8f, grip);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    animate().alpha(1f).setDuration(80).start();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) {
                        return true;
                    }
                    float x = event.getRawX();
                    float y = event.getRawY();
                    float dx = x - lastX;
                    float dy = y - lastY;
                    lastX = x;
                    lastY = y;
                    service.panByScreenDelta(dx, dy);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    animate().alpha(0.65f).setDuration(220).start();
                    return true;
                default:
                    return true;
            }
        }
    }
}
