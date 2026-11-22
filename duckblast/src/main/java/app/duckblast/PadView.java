package app.duckblast;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * The phone's trigger. In motion mode the whole pad fires on touch-down (no
 * waiting for a lift, so it feels instant). In touchpad mode the top part is a
 * drag-to-aim surface and the bottom part is the trigger. A small "TV" preview
 * shows where you're aiming.
 */
final class PadView extends View {
    private final RemoteActivity host;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private int ammo = -1;
    private long flashAt;
    private float lastX, lastY;
    private int dragPointer = -1;

    PadView(RemoteActivity host) {
        super(host);
        this.host = host;
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        setHapticFeedbackEnabled(false);
    }

    void setAmmo(int a) {
        ammo = a;
        invalidate();
    }

    void flash() {
        flashAt = SystemClock.uptimeMillis();
        invalidate();
    }

    private float fireTop() {
        return host.isTouchpad() ? getHeight() * 0.55f : 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float y = e.getY(idx);
                if (y >= fireTop()) {
                    host.fire();
                } else if (dragPointer < 0) {
                    dragPointer = e.getPointerId(idx);
                    lastX = e.getX(idx);
                    lastY = y;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragPointer >= 0) {
                    int i = e.findPointerIndex(dragPointer);
                    if (i >= 0) {
                        host.dragAim(e.getX(i) - lastX, e.getY(i) - lastY, getWidth());
                        lastX = e.getX(i);
                        lastY = e.getY(i);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerId(idx) == dragPointer) dragPointer = -1;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragPointer = -1;
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        int color = host.playerColor();
        float top = fireTop();

        if (host.isTouchpad()) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF1A1F26);
            r.set(0, 0, w, top - dp(10));
            c.drawRoundRect(r, dp(20), dp(20), p);
            p.setColor(0xFF6A7580);
            p.setTextSize(dp(16));
            c.drawText("drag here to aim", w / 2, (top - dp(10)) / 2 + dp(6), p);
        }

        // trigger
        long since = SystemClock.uptimeMillis() - flashAt;
        boolean flashing = since < 120;
        float cx = w / 2, cy = (top + h) / 2;
        float rad = Math.min(w, h - top) * 0.42f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(flashing ? Color.WHITE : (color & 0x00FFFFFF) | 0x33000000);
        c.drawCircle(cx, cy, rad * 1.12f, p);
        p.setColor(flashing ? 0xFFFFF4D6 : color);
        c.drawCircle(cx, cy, rad, p);
        p.setColor(0xFF0E1116);
        p.setTextSize(rad * 0.38f);
        c.drawText("FIRE", cx, cy + rad * 0.13f, p);
        p.setTextSize(rad * 0.12f);
        c.drawText(host.isTouchpad() ? "tap" : "tap anywhere · or volume key", cx, cy + rad * 0.42f, p);
        if (flashing) postInvalidateDelayed(130 - since);

        // shells
        if (ammo >= 0) {
            for (int i = 0; i < GameView.SHELLS; i++) {
                float sx = w - dp(22) - i * dp(20), sy = top + dp(8);
                p.setColor(i < ammo ? 0xFFE8483A : 0x33FFFFFF);
                r.set(sx - dp(6), sy, sx + dp(6), sy + dp(30));
                c.drawRoundRect(r, dp(3), dp(3), p);
            }
        }

        // aim preview: a little TV with your crosshair on it
        float pw = dp(96), ph = pw * 0.5625f;
        float px = dp(8), py = top + dp(8);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF1F2A36);
        r.set(px, py, px + pw, py + ph);
        c.drawRoundRect(r, dp(4), dp(4), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1.5f));
        p.setColor(0xFF5A6570);
        c.drawRoundRect(r, dp(4), dp(4), p);
        float ax = px + clamp(host.aimX()) * pw, ay = py + clamp(host.aimY()) * ph;
        p.setColor(color);
        p.setStrokeWidth(dp(2));
        c.drawCircle(ax, ay, dp(5), p);
        p.setStyle(Paint.Style.FILL);
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
