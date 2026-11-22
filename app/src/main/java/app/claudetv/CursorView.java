package app.claudetv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** A mouse-style pointer drawn over the WebView for "pointer mode". */
public class CursorView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final float size;
    private float x, y;

    public CursorView(Context context) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        size = 26 * d;
        fill.setColor(Color.WHITE);
        stroke.setColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2 * d);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        halo.setColor(0x66D97757);
        setFocusable(false);
        setClickable(false);
        setWillNotDraw(false);
    }

    public void moveTo(float nx, float ny) {
        x = nx;
        y = ny;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawCircle(x, y, size * 0.55f, halo);
        float s = size;
        arrow.reset();
        arrow.moveTo(x, y);
        arrow.lineTo(x, y + s);
        arrow.lineTo(x + s * 0.28f, y + s * 0.74f);
        arrow.lineTo(x + s * 0.48f, y + s * 1.12f);
        arrow.lineTo(x + s * 0.62f, y + s * 1.05f);
        arrow.lineTo(x + s * 0.43f, y + s * 0.68f);
        arrow.lineTo(x + s * 0.78f, y + s * 0.68f);
        arrow.close();
        c.drawPath(arrow, fill);
        c.drawPath(arrow, stroke);
    }
}
