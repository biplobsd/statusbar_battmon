package com.battmon;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class BattMon {
    private static final String TAG = "BattMon";
    private static final String DISABLE_FILE = "/data/local/tmp/battmon/disable";
    private static final String CONFIG_FILE = "/data/local/tmp/battmon/config.json";

    private static final String SYS_TEMP = "/sys/class/power_supply/battery/temp";
    private static final String SYS_VOLTAGE = "/sys/class/power_supply/battery/voltage_now";
    private static final String SYS_CURRENT = "/sys/class/power_supply/battery/current_now";
    private static final String SYS_STATUS = "/sys/class/power_supply/battery/status";

    private static final Pattern TIME_PATTERN = Pattern.compile("^\\d{1,2}:\\d{2}$");

    private static BattMon sInstance;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mScreenOn = true;
    private int mAttachRetries = 0;

    private BattMonView mInjectedView;
    private TextView mClockView;
    private long mLastConfigMtime = 0;

    // Config defaults
    private boolean mEnabled = true;
    private boolean mDualLine = true;
    private int mContentMode = 1; // 1: Temp & Watt, 2: Watt only, 3: Temp only, 4: Temp & mA
    private int mRefreshMs = 1000;
    private String mTempUnit = "°C";
    private String mPowerUnit = "W";
    private float mFontSizeSp = 6.5f;
    private boolean mBoldFont = false;
    private boolean mChargingOnly = false;
    private float mPaddingTopDp = 1.5f;
    private float mPaddingLeftDp = 0.0f;

    private final Runnable mTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mScreenOn) return;
            try {
                checkConfigReload();
                if (mEnabled) {
                    if (mInjectedView == null || !mInjectedView.isAttachedToWindow()) {
                        tryAttachView();
                    }
                    sampleAndUpdate();
                } else if (mInjectedView != null) {
                    mInjectedView.setVisibility(View.GONE);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error in ticker", t);
            }
            mHandler.postDelayed(this, Math.max(250, mRefreshMs));
        }
    };

    public static void init(Context context) {
        if (sInstance != null) return;
        try {
            if (new File(DISABLE_FILE).exists()) {
                Log.w(TAG, "Disabled via escape hatch: " + DISABLE_FILE);
                return;
            }
            sInstance = new BattMon(context);
            sInstance.start();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize BattMon", t);
        }
    }

    private BattMon(Context context) {
        mContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
    }

    private void start() {
        Log.i(TAG, "BattMon starting on HyperOS SystemUI...");
        loadConfig();

        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            mScreenOn = pm.isInteractive();
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    mScreenOn = false;
                    mHandler.removeCallbacks(mTickRunnable);
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) ||
                           Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    if (!mScreenOn) {
                        mScreenOn = true;
                        mHandler.removeCallbacks(mTickRunnable);
                        mHandler.post(mTickRunnable);
                    }
                }
            }
        }, filter);

        initDarkIconDispatcher();

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                boolean attached = tryAttachView();
                if (!attached && mAttachRetries < 60) {
                    mAttachRetries++;
                    mHandler.postDelayed(this, 500);
                } else {
                    mHandler.post(mTickRunnable);
                }
            }
        });
    }

    private boolean tryAttachView() {
        try {
            Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
            Object wmg = wmgClass.getMethod("getInstance").invoke(null);
            Field viewsField = wmgClass.getDeclaredField("mViews");
            viewsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<View> rootViews = (List<View>) viewsField.get(wmg);
            if (rootViews == null || rootViews.isEmpty()) return false;

            for (View root : rootViews) {
                TextView clock = findClockView(root);
                if (clock != null && clock.getParent() instanceof ViewGroup) {
                    ViewGroup parent = (ViewGroup) clock.getParent();
                    View existing = parent.findViewWithTag(BattMonView.VIEW_TAG);
                    if (existing instanceof BattMonView) {
                        mInjectedView = (BattMonView) existing;
                        mClockView = clock;
                        mInjectedView.setVisibility(mEnabled ? View.VISIBLE : View.GONE);
                        return true;
                    }

                    BattMonView view = new BattMonView(parent.getContext());
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    lp.gravity = Gravity.CENTER_VERTICAL;
                    view.setLayoutParams(lp);

                    int clockIdx = parent.indexOfChild(clock);
                    int insertIdx = clockIdx >= 0 ? clockIdx + 1 : 0;
                    parent.addView(view, insertIdx);

                    mInjectedView = view;
                    mClockView = clock;
                    mInjectedView.setVisibility(mEnabled ? View.VISIBLE : View.GONE);
                    Log.i(TAG, "Successfully attached BattMonView next to clock at index " + insertIdx);
                    try {
                        Typeface tf = clock.getTypeface();
                        int weight = (tf != null && android.os.Build.VERSION.SDK_INT >= 28) ? tf.getWeight() : -1;
                        boolean isB = tf != null && tf.isBold();
                        Log.i(TAG, "CLOCK_DIAG: class=" + clock.getClass().getName()
                                + ", text='" + clock.getText() + "'"
                                + ", pxSize=" + clock.getTextSize()
                                + ", tf=" + tf
                                + ", weight=" + weight
                                + ", isBold=" + isB
                                + ", incPad=" + clock.getIncludeFontPadding()
                                + ", gravity=" + clock.getGravity()
                                + ", fvs=" + clock.getFontVariationSettings()
                                + ", pad=[" + clock.getPaddingLeft() + "," + clock.getPaddingTop() + "," + clock.getPaddingRight() + "," + clock.getPaddingBottom() + "]"
                                + ", baseline=" + clock.getBaseline()
                                + ", lp=" + clock.getLayoutParams()
                                + ", parent=" + parent.getClass().getName()
                                + ", parentH=" + parent.getHeight()
                                + ", clockY=" + clock.getY() + ", clockTop=" + clock.getTop() + ", clockH=" + clock.getHeight());
                    } catch (Throwable t) {
                        Log.e(TAG, "Error logging clock diag", t);
                    }
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in tryAttachView", t);
        }
        return false;
    }

    private TextView findClockView(View view) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            CharSequence cs = tv.getText();
            if (cs != null && TIME_PATTERN.matcher(cs.toString().trim()).matches()) {
                return tv;
            }
            try {
                String resName = view.getResources().getResourceEntryName(view.getId());
                if (resName != null && resName.toLowerCase(Locale.ROOT).contains("clock")) {
                    return tv;
                }
            } catch (Throwable ignored) {}
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                TextView found = findClockView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void initDarkIconDispatcher() {
        try {
            Class<?> depClass = Class.forName("com.android.systemui.Dependency");
            Class<?> darkDispatcherClass = Class.forName("com.android.systemui.plugins.DarkIconDispatcher");
            Method getMethod = depClass.getMethod("get", Class.class);
            Object dispatcher = getMethod.invoke(null, darkDispatcherClass);
            if (dispatcher != null) {
                Class<?> receiverClass = Class.forName("com.android.systemui.plugins.DarkIconDispatcher$DarkReceiver");
                Object proxy = Proxy.newProxyInstance(
                        receiverClass.getClassLoader(),
                        new Class<?>[]{receiverClass},
                        (proxyObj, method, args) -> {
                            if ("onDarkChanged".equals(method.getName()) && args != null && args.length >= 3) {
                                if (args[2] instanceof Integer) {
                                    final int tint = (Integer) args[2];
                                    if (mInjectedView != null) {
                                        mInjectedView.post(() -> mInjectedView.setTextColor(tint));
                                    }
                                }
                            }
                            return null;
                        }
                );
                Method addReceiver = darkDispatcherClass.getMethod("addDarkReceiver", receiverClass);
                addReceiver.invoke(dispatcher, proxy);
                Log.i(TAG, "DarkIconDispatcher receiver registered successfully");
            }
        } catch (Throwable t) {
            Log.d(TAG, "DarkIconDispatcher registration skipped: " + t.getMessage());
        }
    }

    private void sampleAndUpdate() {
        if (mInjectedView == null) return;

        int rawTemp = readIntFile(SYS_TEMP, 0);
        int rawVolt = readIntFile(SYS_VOLTAGE, 0);
        int rawCurr = readIntFile(SYS_CURRENT, 0);

        if (mClockView != null && mClockView.getVisibility() != View.VISIBLE) {
            if (mInjectedView.getVisibility() != View.GONE) {
                mInjectedView.setVisibility(View.GONE);
            }
            return;
        }

        boolean isCharging = isDeviceCharging();
        if (mChargingOnly && !isCharging) {
            if (mInjectedView.getVisibility() != View.GONE) {
                mInjectedView.setVisibility(View.GONE);
            }
            return;
        }

        if (mInjectedView.getVisibility() != View.VISIBLE) {
            mInjectedView.setVisibility(View.VISIBLE);
        }

        float tempC = rawTemp / 10.0f;
        double volts = rawVolt / 1000000.0;
        double amps = Math.abs(rawCurr) / 1000000.0;
        double watts = volts * amps;
        int currMa = (int) (Math.abs(rawCurr) / 1000);

        String line1 = "";
        String line2 = "";

        switch (mContentMode) {
            case 2: // Watt only
                line1 = String.format(Locale.US, "%.2f%s", watts, mPowerUnit);
                break;
            case 3: // Temp only
                line1 = String.format(Locale.US, "%.1f%s", tempC, mTempUnit);
                break;
            case 4: // Temp & Current
                line1 = String.format(Locale.US, "%.1f%s", tempC, mTempUnit);
                line2 = String.format(Locale.US, "%dmA", currMa);
                break;
            case 5: // Current & Watt
                line1 = String.format(Locale.US, "%dmA", currMa);
                line2 = String.format(Locale.US, "%.2f%s", watts, mPowerUnit);
                break;
            case 1: // Temp & Watt
            default:
                line1 = String.format(Locale.US, "%.1f%s", tempC, mTempUnit);
                line2 = String.format(Locale.US, "%.2f%s", watts, mPowerUnit);
                break;
        }

        int targetColor = 0;
        Typeface clockTf = null;
        float clockTextSize = 0f;
        if (mClockView != null) {
            targetColor = mClockView.getCurrentTextColor();
            clockTf = mClockView.getTypeface();
            clockTextSize = mClockView.getTextSize();
        }

        if (!mDualLine || mContentMode == 2 || mContentMode == 3) {
            String combined = line2.isEmpty() ? line1 : (line1 + " " + line2);
            mInjectedView.updateContent(combined, "", false, mFontSizeSp, mBoldFont, targetColor, clockTf, clockTextSize, mPaddingTopDp, mPaddingLeftDp);
        } else {
            mInjectedView.updateContent(line1, line2, true, mFontSizeSp, mBoldFont, targetColor, clockTf, clockTextSize, mPaddingTopDp, mPaddingLeftDp);
        }
    }

    private boolean isDeviceCharging() {
        try {
            File f = new File(SYS_STATUS);
            if (f.exists()) {
                String status = readStringFile(f);
                return "Charging".equalsIgnoreCase(status) || "Full".equalsIgnoreCase(status);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void checkConfigReload() {
        File cfg = new File(CONFIG_FILE);
        if (cfg.exists() && cfg.lastModified() != mLastConfigMtime) {
            loadConfig();
        }
    }

    private void loadConfig() {
        try {
            File cfg = new File(CONFIG_FILE);
            if (!cfg.exists()) return;
            mLastConfigMtime = cfg.lastModified();
            String json = readStringFile(cfg);
            if (json == null || json.trim().isEmpty()) return;

            JSONObject obj = new JSONObject(json);
            mEnabled = obj.optBoolean("enabled", true);
            mDualLine = "dual_line".equalsIgnoreCase(obj.optString("layout_mode", "dual_line"));
            mContentMode = obj.optInt("content_mode", 1);
            mRefreshMs = obj.optInt("refresh_ms", 1000);
            mTempUnit = obj.optString("temp_unit", "°C");
            mPowerUnit = obj.optString("power_unit", "W");
            float defFontSize = mDualLine ? 6.5f : 6.0f;
            float defPadTop = mDualLine ? 1.5f : 0.5f;
            mFontSizeSp = (float) obj.optDouble("font_size_sp", defFontSize);
            mBoldFont = obj.optBoolean("bold_font", false);
            mChargingOnly = obj.optBoolean("charging_only", false);
            mPaddingTopDp = (float) obj.optDouble("padding_top_dp", defPadTop);
            mPaddingLeftDp = (float) obj.optDouble("padding_left_dp", 0.0);
            Log.i(TAG, "Config loaded: enabled=" + mEnabled + ", dualLine=" + mDualLine + ", padTop=" + mPaddingTopDp + ", padLeft=" + mPaddingLeftDp + ", fontSize=" + mFontSizeSp);
        } catch (Throwable t) {
            Log.e(TAG, "Error loading config", t);
        }
    }

    private static int readIntFile(String path, int def) {
        try (FileInputStream fis = new FileInputStream(path)) {
            byte[] buf = new byte[32];
            int read = fis.read(buf);
            if (read > 0) {
                String str = new String(buf, 0, read).trim();
                return Integer.parseInt(str);
            }
        } catch (Throwable ignored) {}
        return def;
    }

    private static String readStringFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[(int) Math.min(file.length() + 32, 4096)];
            int read = fis.read(buf);
            if (read > 0) {
                return new String(buf, 0, read).trim();
            }
        } catch (Throwable ignored) {}
        return "";
    }
}
