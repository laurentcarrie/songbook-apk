package com.laurentcarrie.zik;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Four dots, one per beat of the bar, that light up in time with the drum loop, and the bar counter. */
final class BeatIndicator extends View {
    private static final int ACCENT = Color.rgb(194, 24, 91), BEAT = Color.rgb(240, 98, 146), OFF = Color.rgb(200, 200, 200);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DrumMachine drums;
    private final int heightPx;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            invalidate();
            postDelayed(this, 30);
        }
    };

    BeatIndicator(Context context, int heightPx) {
        super(context);
        this.drums = DrumMachine.get(context);
        this.heightPx = heightPx;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(ticker);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), heightPx);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int[] pos = drums.position(); // {bar, beat} or null when nothing plays
        float h = getHeight(), w = getWidth();
        float r = h * 0.28f;
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(h * 0.32f);
        paint.setColor(Color.GRAY);
        String label = pos == null ? "" : pos[0] < 0 ? "décompte" : "mesure " + (pos[0] + 1) + " / " + drums.getLoopBars();
        canvas.drawText(label, h * 0.3f, h * 0.62f, paint);
        float start = w * 0.42f, step = (w - start - h * 0.5f) / 3f;
        for (int i = 0; i < 4; i++) {
            boolean on = pos != null && pos[1] == i;
            paint.setColor(!on ? OFF : i == 0 ? ACCENT : BEAT);
            canvas.drawCircle(start + i * step, h / 2f, on ? r * 1.25f : r * 0.8f, paint);
        }
    }
}
