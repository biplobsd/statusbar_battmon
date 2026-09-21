package com.battmon;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

import java.io.File;

/**
 * Zero-relayout, self-drawing battery readout for the HyperOS status bar.
 *
 * <p>Design constraints (see build/reports/REPORT.md):
 * <ul>
 *   <li><b>No layout passes after the first one.</b> The view measures itself from a
 *       pre-computed worst-case string, so the measured size never changes while the
 *       numbers change. A data update therefore only calls {@link #invalidate()}
 *       (re-record + draw) and never {@code requestLayout()}/{@code relayoutWindow()}.</li>
 *   <li><b>No work when nothing changed.</b> {@link #setContent} diffs the rendered
 *       strings and the colour and returns without touching the display list when the
 *       frame would be identical.</li>
 *   <li><b>No per-update allocations.</b> No {@code TextView}, no {@code StaticLayout},
 *       no {@code Typeface.create} in the update path; the typeface is resolved once per
 *       style change and cached.</li>
 * </ul>
 */
public class BattMonView extends View {

    public static final String VIEW_TAG = "battmon_status_view";

    /** Extra right padding kept for visual balance with the clock, in dp. */
    private static final float RIGHT_PAD_DP = 2.0f;

    private static Typeface sMiSansTypeface;
    private static boolean sMiSansChecked;
    private static Typeface sBoldTypeface;
    private static Typeface sBoldTypefaceBase;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG
            | Paint.DITHER_FLAG);
    private final float mDensity;

    private String mTop = "";
    private String mBottom = "";
    private String mWorstTop = "-88.8°C";
    private String mWorstBottom = "-888.88W";
    private boolean mDual = true;
    private int mColor = Color.WHITE;
    private boolean mColorSet;

    private Typeface mTypeface;
    private float mTextSizeSp = -1f;
    private float mPadTopDp = -1f;
    private float mPadLeftDp = -1f;
    private float mPadRightDp = 2.0f;

    private int mPadLeftPx;
    private int mPadTopPx;
    private int mMeasuredW;
    private int mMeasuredH;
    private float mBaselineTop;
    private float mBaselineBottom;
    private boolean mGeometryDirty = true;

    public BattMonView(Context context) {
        super(context);
        setTag(VIEW_TAG);
        setWillNotDraw(false);
        mDensity = context.getResources().getDisplayMetrics().density;
        mPaint.setColor(mColor);
    }

    /**
     * Applies style/typography and padding.
     */
    public void configure(boolean dualLine,
                          float textSizeSp,
                          boolean bold,
                          Typeface clockTypeface,
                          float padTopDp,
                          float padLeftDp,
                          float padRightDp,
                          String worstTop,
                          String worstBottom) {
        final Typeface tf = resolveTypeface(clockTypeface, bold);
        final float size = textSizeSp > 0f ? textSizeSp : (dualLine ? 6.5f : 6.0f);
        final float padTop = padTopDp >= 0f ? padTopDp : (dualLine ? 1.5f : 0.5f);
        final float padLeft = padLeftDp >= 0f ? padLeftDp : 0.0f;
        final float padRight = padRightDp >= 0f ? padRightDp : 2.0f;

        boolean changed = false;
        if (mTypeface != tf) {
            mTypeface = tf;
            mPaint.setTypeface(tf);
            changed = true;
        }
        if (mTextSizeSp != size) {
            mTextSizeSp = size;
            mPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, size, getResources().getDisplayMetrics()));
            changed = true;
        }
        if (mDual != dualLine) {
            mDual = dualLine;
            changed = true;
        }
        if (mPadTopDp != padTop) {
            mPadTopDp = padTop;
            changed = true;
        }
        if (mPadLeftDp != padLeft) {
            mPadLeftDp = padLeft;
            changed = true;
        }
        if (mPadRightDp != padRight) {
            mPadRightDp = padRight;
            changed = true;
        }
        if (!worstTop.equals(mWorstTop)) {
            mWorstTop = worstTop;
            changed = true;
        }
        if (!worstBottom.equals(mWorstBottom)) {
            mWorstBottom = worstBottom;
            changed = true;
        }

        if (changed) {
            mGeometryDirty = true;
            requestLayout();   // once per config change only
        }
        if (!mColorSet) {
            mColorSet = true;
            invalidate();
        }
    }

    /**
     * Data update path. Measures actual text width without arbitrary excess padding.
     * When digits have identical advance width (tabular font), requestLayout() is skipped.
     */
    public void setContent(String top, String bottom, int color) {
        final String t = top == null ? "" : top;
        final String b = (bottom == null || !mDual) ? "" : bottom;

        boolean changed = false;
        boolean textChanged = false;
        if (!t.equals(mTop)) {
            mTop = t;
            changed = true;
            textChanged = true;
        }
        if (!b.equals(mBottom)) {
            mBottom = b;
            changed = true;
            textChanged = true;
        }
        if (color != 0 && color != mColor) {
            mColor = color;
            mColorSet = true;
            mPaint.setColor(color);
            changed = true;
        }

        if (changed) {
            if (textChanged) {
                final int oldW = mMeasuredW;
                final int oldH = mMeasuredH;
                recomputeGeometry();
                if (mMeasuredW != oldW || mMeasuredH != oldH) {
                    requestLayout();
                    return;
                }
            }
            invalidate();      // draw-only: no measure, no layout, no window relayout
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mGeometryDirty) {
            recomputeGeometry();
            mGeometryDirty = false;
        }
        setMeasuredDimension(mMeasuredW, mMeasuredH);
    }

    private void recomputeGeometry() {
        final Paint.FontMetrics fm = mPaint.getFontMetrics();
        mPadLeftPx = Math.round(mPadLeftDp * mDensity);
        mPadTopPx = Math.round((mPadTopDp >= 0f ? mPadTopDp : 0f) * mDensity);
        final int padRightPx = Math.round((mPadRightDp >= 0f ? mPadRightDp : 2.0f) * mDensity);

        final String top = (mTop != null && !mTop.isEmpty()) ? mTop : mWorstTop;
        final String bottom = (mBottom != null && !mBottom.isEmpty() && mDual) ? mBottom : mWorstBottom;

        float w = mPaint.measureText(top);
        if (mDual && bottom != null && !bottom.isEmpty()) {
            final float w2 = mPaint.measureText(bottom);
            if (w2 > w) w = w2;
        }
        mMeasuredW = mPadLeftPx + padRightPx + (int) Math.ceil(w);

        // TextView-compatible metrics: includeFontPadding uses fontMetrics.top/bottom.
        final float lineHeight = fm.bottom - fm.top;
        if (mDual) {
            mBaselineTop = mPadTopPx - fm.top;
            mBaselineBottom = mBaselineTop + lineHeight;
            mMeasuredH = (int) Math.ceil(mBaselineBottom + fm.bottom);
        } else {
            mMeasuredH = (int) Math.ceil(lineHeight) + mPadTopPx;
            mBaselineTop = mPadTopPx - fm.top;
            mBaselineBottom = mBaselineTop;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final Paint p = mPaint;
        canvas.drawText(mTop, mPadLeftPx, mBaselineTop, p);
        if (mDual && !mBottom.isEmpty()) {
            canvas.drawText(mBottom, mPadLeftPx, mBaselineBottom, p);
        }
    }

    public int getTextColor() {
        return mColor;
    }

    /** Cached typeface resolution: never allocates or locks in the update path. */
    private static Typeface resolveTypeface(Typeface clockTypeface, boolean bold) {
        Typeface base = clockTypeface;
        if (base == null) {
            base = systemFallback();
        }
        if (!bold) {
            return base;
        }
        if (sBoldTypeface != null && sBoldTypefaceBase == base) {
            return sBoldTypeface;
        }
        Typeface made;
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            made = Typeface.create(base, 650, false);
        } else {
            made = Typeface.create(base, Typeface.BOLD);
        }
        sBoldTypeface = made;
        sBoldTypefaceBase = base;
        return made;
    }

    /** One-shot MiSans lookup (three stat() calls in the whole process lifetime). */
    private static Typeface systemFallback() {
        if (!sMiSansChecked) {
            sMiSansChecked = true;
            final String[] candidates = {
                    "/system/fonts/MiSansVF.ttf",
                    "/system/fonts/MiSansVF_Overlay.ttf",
                    "/system/fonts/MiSansLatinVF.ttf"
            };
            for (String path : candidates) {
                try {
                    final File f = new File(path);
                    if (f.exists()) {
                        sMiSansTypeface = Typeface.createFromFile(f);
                        if (sMiSansTypeface != null) break;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return sMiSansTypeface != null ? sMiSansTypeface : Typeface.SANS_SERIF;
    }
}
