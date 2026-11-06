package com.laurentcarrie.zik;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Lays its children out left to right, and goes on to the next row when a child does not fit. */
final class FlowLayout extends ViewGroup {
    FlowLayout(Context context) {
        super(context);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int maxWidth = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0, rowHeight = 0, height = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            measureChild(c, MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            if (x > 0 && x + c.getMeasuredWidth() > maxWidth) { // next row
                height += rowHeight;
                x = 0;
                rowHeight = 0;
            }
            x += c.getMeasuredWidth();
            rowHeight = Math.max(rowHeight, c.getMeasuredHeight());
        }
        height += rowHeight;
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), height + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxWidth = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, rowHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            if (x > 0 && x + c.getMeasuredWidth() > maxWidth) {
                y += rowHeight;
                x = 0;
                rowHeight = 0;
            }
            c.layout(getPaddingLeft() + x, getPaddingTop() + y,
                    getPaddingLeft() + x + c.getMeasuredWidth(), getPaddingTop() + y + c.getMeasuredHeight());
            x += c.getMeasuredWidth();
            rowHeight = Math.max(rowHeight, c.getMeasuredHeight());
        }
    }
}
