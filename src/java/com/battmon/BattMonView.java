package com.battmon;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

public class BattMonView extends LinearLayout {
    public static final String VIEW_TAG = "battmon_status_view";

    private static Typeface sMiSansTypeface;
    private static boolean sMiSansChecked = false;

    private final TextView mTopText;
    private final TextView mBottomText;
    private int mCurrentColor = Color.WHITE;
    private String mLastTop = "";
    private String mLastBottom = "";
    private boolean mLastDual = true;
    private float mLastPadTop = -999f;
    private float mLastPadLeft = -999f;

    public BattMonView(Context context) {
        super(context);
        setTag(VIEW_TAG);
        setOrientation(LinearLayout.VERTICAL);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.START);

        int padLeft = dpToPx(context, 0.0f);
        int padTop = dpToPx(context, 1.5f);
        int padRight = dpToPx(context, 2.0f);
        setPaddingRelative(padLeft, padTop, padRight, 0);

        mTopText = new TextView(context);
        mTopText.setIncludeFontPadding(true);
        mTopText.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        mTopText.setSingleLine(true);

        mBottomText = new TextView(context);
        mBottomText.setIncludeFontPadding(true);
        mBottomText.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        mBottomText.setSingleLine(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        addView(mTopText, lp);
        addView(mBottomText, lp);

        setTextColor(mCurrentColor);
    }

    public void updateContent(
            String top,
            String bottom,
            boolean isDualLine,
            float textSizeSp,
            boolean bold,
            int color,
            Typeface clockTypeface,
            float clockTextSizePx,
            float padTopDp,
            float padLeftDp
    ) {
        if (mCurrentColor != color && color != 0) {
            setTextColor(color);
        }

        // Apply padding adjustments consistently
        if (padTopDp != mLastPadTop || padLeftDp != mLastPadLeft || isDualLine != mLastDual) {
            float defaultPadTop = isDualLine ? 1.5f : 0.5f;
            float effPadTop = padTopDp >= 0 ? padTopDp : defaultPadTop;
            int pTop = dpToPx(getContext(), effPadTop);
            int pLeft = dpToPx(getContext(), padLeftDp >= 0 ? padLeftDp : 0.0f);
            int pRight = dpToPx(getContext(), 2.0f);
            setPaddingRelative(pLeft, pTop, pRight, 0);
            mLastPadTop = padTopDp;
            mLastPadLeft = padLeftDp;
        }

        // Get authentic Clock / MiSans typeface
        Typeface tf = resolveTypeface(clockTypeface, bold);

        if (isDualLine) {
            if (mBottomText.getVisibility() != View.VISIBLE) {
                mBottomText.setVisibility(View.VISIBLE);
            }
            float sz = textSizeSp > 0 ? textSizeSp : 6.5f;
            mTopText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sz);
            mBottomText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sz);
            mTopText.setTypeface(tf);
            mBottomText.setTypeface(tf);

            if (!top.equals(mLastTop)) {
                mTopText.setText(top);
                mLastTop = top;
            }
            if (!bottom.equals(mLastBottom)) {
                mBottomText.setText(bottom);
                mLastBottom = bottom;
            }
        } else {
            if (mBottomText.getVisibility() != View.GONE) {
                mBottomText.setVisibility(View.GONE);
            }
            // Single-line uses user-specified/default font size (default 6.0sp)
            float sz = textSizeSp > 0 ? textSizeSp : 6.0f;
            mTopText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sz);
            mTopText.setTypeface(tf);

            if (!top.equals(mLastTop)) {
                mTopText.setText(top);
                mLastTop = top;
            }
        }
        mLastDual = isDualLine;
    }

    private static Typeface resolveTypeface(Typeface clockTf, boolean bold) {
        Typeface base = clockTf;
        if (base == null) {
            if (!sMiSansChecked) {
                sMiSansChecked = true;
                String[] candidates = {
                    "/system/fonts/MiSansVF.ttf",
                    "/system/fonts/MiSansVF_Overlay.ttf",
                    "/system/fonts/MiSansLatinVF.ttf"
                };
                for (String path : candidates) {
                    File f = new File(path);
                    if (f.exists()) {
                        try {
                            sMiSansTypeface = Typeface.createFromFile(f);
                            if (sMiSansTypeface != null) break;
                        } catch (Throwable ignored) {}
                    }
                }
            }
            base = sMiSansTypeface != null ? sMiSansTypeface : Typeface.SANS_SERIF;
        }

        if (bold) {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return Typeface.create(base, 650, false);
            } else {
                return Typeface.create(base, Typeface.BOLD);
            }
        }
        return base;
    }

    public void setTextColor(int color) {
        mCurrentColor = color;
        mTopText.setTextColor(color);
        mBottomText.setTextColor(color);
    }

    public int getCurrentColor() {
        return mCurrentColor;
    }

    private static int dpToPx(Context context, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics()
        ));
    }
}
