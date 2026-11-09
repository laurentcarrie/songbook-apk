package com.laurentcarrie.zik;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashSet;
import java.util.Set;

/** A vertical strip "# A B ... Z": touching or dragging on a letter reports it. */
final class IndexBar extends View {
    interface Listener { void onLetter(char letter); }

    static final String LETTERS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int ACTIVE = Color.rgb(25, 100, 200), DIM = Color.rgb(200, 200, 200);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Set<Character> present = new HashSet<>();
    private Listener listener;

    IndexBar(Context c) {
        super(c);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
    }

    void setListener(Listener l) { listener = l; }

    /** The letters that have at least one song: the others are dimmed. */
    void setPresent(Set<Character> p) {
        present = p;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cell = getHeight() / (float) LETTERS.length();
        paint.setTextSize(Math.min(cell * 0.7f, getWidth() * 0.7f));
        for (int i = 0; i < LETTERS.length(); i++) {
            char ch = LETTERS.charAt(i);
            paint.setColor(present.contains(ch) ? ACTIVE : DIM);
            float y = cell * i + cell / 2 - (paint.descent() + paint.ascent()) / 2;
            canvas.drawText(String.valueOf(ch), getWidth() / 2f, y, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
                int i = (int) (e.getY() / getHeight() * LETTERS.length());
                i = Math.max(0, Math.min(LETTERS.length() - 1, i));
                if (listener != null) listener.onLetter(LETTERS.charAt(i));
                return true;
            default:
                return true;
        }
    }
}
