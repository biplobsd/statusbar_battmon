package com.battmon;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * HyperOS status-bar battery monitor.
 *
 * <p>Key Architecture Principles:
 * <ul>
 *   <li><b>Zero UI-thread blocking / Zero Main thread I/O</b>: Battery sysfs reads are executed
 *       strictly on a low-priority background HandlerThread ({@link BattMonSampler}).</li>
 *   <li><b>Zero WindowManagerService / system_server IPC load</b>: {@link BattMonView} has fixed
 *       measured geometry based on worst-case bounds. Data updates only call draw-only
 *       {@link View#invalidate()} and never {@link View#requestLayout()}.</li>
 *   <li><b>Zero WakeLock / Uninhibited Deep Sleep & Dozing</b>: When the screen turns off
 *       ({@link Intent#ACTION_SCREEN_OFF}), all tickers and background sampling are immediately
 *       halted. Zero timers run while the panel is off. Zero wake locks are held.</li>
 *   <li><b>Live Real-Time Power Monitoring</b>: While the screen is ON and the view is visible,
 *       updates occur smoothly at the user-configured {@code refresh_ms} (default 1000ms).</li>
 *   <li><b>Zero-stat() Config Watching</b>: Changes to {@code config.json} are observed live
 *       via inotify ({@link FileObserver}).</li>
 * </ul>
 */
public class BattMon {

    private static final String TAG = "BattMon";

    private static final String CONFIG_DIR = "/data/local/tmp/battmon";
    private static final String DISABLE_FILE = CONFIG_DIR + "/disable";
    private static final String CONFIG_FILE = CONFIG_DIR + "/config.json";

    /** Attach retry timing. */
    private static final long ATTACH_FIRST_DELAY_MS = 120L;
    private static final long ATTACH_MAX_DELAY_MS = 2500L;

    /** Fallback config check (only used if inotify is unavailable). */
    private static final long CONFIG_CHECK_MIN_INTERVAL_MS = 5000L;

    /** Bounded view-tree scan so a pathological hierarchy cannot burn CPU. */
    private static final int MAX_SCAN_DEPTH = 64;
    private static final int MAX_SCAN_VIEWS = 4000;

    /** Clock text pattern supporting 24h, 12h, seconds, and AM/PM formats. */
    private static final Pattern CLOCK_PATTERN =
            Pattern.compile("^\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[AaPp][Mm])?$");

    /** power_source modes */
    public static final int POWER_LIVE = 0;     // Live background sampling at refresh_ms (Recommended)
    public static final int POWER_EVENT = 1;    // Sysfs read per system battery broadcast
    public static final int POWER_COUNTER = 2;  // Charge counter only (zero sysfs I/O, coarse)

    private static final String EXTRA_CHARGE_COUNTER = "charge_counter";
    private static final long COUNTER_MIN_DELTA_UAH = 100L;

    private static BattMon sInstance;

    // ------------------------------------------------------------------ cached reflection
    private static Method sGetWmgInstance;
    private static Field sWmgViewsField;
    private static boolean sReflectionReady;

    // ------------------------------------------------------------------ state
    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final BattMonSampler mSampler = new BattMonSampler();

    private BattMonView mView;
    private TextView mClock;
    private boolean mViewStyleDirty = true;
    private boolean mScreenOn = true;
    private boolean mAttachPending;
    private int mAttachTries;

    private FileObserver mConfigObserver;
    private boolean mObserverRunning;
    private volatile long mLastConfigCheckMs;
    private long mLastConfigMtime;

    // ------------------------------------------------------------------ config
    private boolean mEnabled = true;
    private boolean mDualLine = true;
    private int mContentMode = 1;
    private int mRefreshMs = 1000;
    private String mTempUnit = "\u00b0C";
    private String mPowerUnit = "W";
    private float mFontSizeSp = 6.5f;
    private boolean mBoldFont;
    private boolean mChargingOnly;
    private float mPaddingTopDp = 1.5f;
    private float mPaddingLeftDp;
    private float mPaddingRightDp = 2.0f;
    private int mPowerSource = POWER_LIVE;

    private String mWorstTop = "35.0\u00b0C";
    private String mWorstBottom = "1.50W";

    // ------------------------------------------------------------------ battery snapshot
    private boolean mHaveBattery;
    private int mTempTenths = 250;
    private int mVoltageMv = 3800;
    private int mStatus = 1;      // BatteryManager.BATTERY_STATUS_UNKNOWN
    private int mPlugged;

    private boolean mHaveCounter;
    private boolean mCounterAnchorSticky;
    private long mCounterUah;
    private long mCounterAtMs;

    private boolean mHaveCurrent;
    private double mCurrentMa;
    private int mSysfsFailures;

    private boolean mSamplingActive;
    private long mLastLogMs;
    private long mLastBatteryLogMs;

    private final StringBuilder mSb = new StringBuilder(32);

    // ================================================================== lifecycle

    public static void init(Context context) {
        if (sInstance != null) {
            return;
        }
        try {
            if (new File(DISABLE_FILE).exists()) {
                Log.w(TAG, "Disabled via escape hatch: " + DISABLE_FILE);
                return;
            }
            final BattMon instance = new BattMon(context);
            sInstance = instance;
            instance.start();
        } catch (Throwable t) {
            Log.e(TAG, "Init failed", t);
        }
    }

    private BattMon(Context context) {
        final Context app = context.getApplicationContext();
        mContext = app != null ? app : context;
    }

    private void start() {
        loadConfig();
        prepareWorstCaseStrings();

        final PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            mScreenOn = pm.isInteractive();
        }

        startConfigObserver();
        registerReceivers();
        initDarkIconDispatcher();

        if (mView == null) {
            scheduleAttach();
        }
        if (mScreenOn && mEnabled && mPowerSource == POWER_LIVE) {
            startSampling();
        }
        Log.i(TAG, "BattMon started: enabled=" + mEnabled
                + " dual=" + mDualLine + " mode=" + mContentMode + " interval=" + mRefreshMs
                + " powerSource=" + mPowerSource);
    }

    // ================================================================== config

    private void startConfigObserver() {
        try {
            final File dir = new File(CONFIG_DIR);
            if (!dir.isDirectory()) {
                return;
            }
            mConfigObserver = new FileObserver(dir,
                    FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.CREATE
                            | FileObserver.DELETE) {
                @Override
                public void onEvent(int event, String path) {
                    if (path != null && (path.startsWith("config.json") || path.startsWith("disable"))) {
                        mMain.removeCallbacks(mReloadRunnable);
                        mMain.post(mReloadRunnable);
                    }
                }
            };
            mConfigObserver.startWatching();
            mObserverRunning = true;
        } catch (Throwable t) {
            mObserverRunning = false;
            Log.w(TAG, "inotify unavailable, using throttled mtime check: " + t);
        }
    }

    private final Runnable mReloadRunnable = new Runnable() {
        @Override
        public void run() {
            if (new File(DISABLE_FILE).exists()) {
                mEnabled = false;
                stopSampling();
                hide();
                return;
            }
            loadConfig();
            prepareWorstCaseStrings();
            mViewStyleDirty = true;
            mAttachTries = 0;
            if (mScreenOn && mEnabled && mPowerSource == POWER_LIVE) {
                startSampling();
            } else {
                stopSampling();
            }
            refresh();
        }
    };

    private void maybeCheckConfigFallback() {
        if (mObserverRunning) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (now - mLastConfigCheckMs < CONFIG_CHECK_MIN_INTERVAL_MS) {
            return;
        }
        mLastConfigCheckMs = now;
        final File cfg = new File(CONFIG_FILE);
        if (cfg.exists() && cfg.lastModified() != mLastConfigMtime) {
            mMain.post(mReloadRunnable);
        }
    }

    private void loadConfig() {
        try {
            final File cfg = new File(CONFIG_FILE);
            if (!cfg.exists()) {
                return;
            }
            mLastConfigMtime = cfg.lastModified();
            final String json = readStringFile(cfg);
            if (json == null || json.trim().isEmpty()) {
                return;
            }
            final JSONObject obj = new JSONObject(json);
            mEnabled = obj.optBoolean("enabled", true);
            mDualLine = "dual_line".equalsIgnoreCase(obj.optString("layout_mode", "dual_line"));
            mContentMode = obj.optInt("content_mode", 1);
            mRefreshMs = obj.optInt("refresh_ms", 1000);
            mTempUnit = obj.optString("temp_unit", "\u00b0C");
            mPowerUnit = obj.optString("power_unit", "W");
            final float defFont = mDualLine ? 6.5f : 6.0f;
            final float defPadTop = mDualLine ? 1.5f : 0.5f;
            mFontSizeSp = (float) obj.optDouble("font_size_sp", defFont);
            mBoldFont = obj.optBoolean("bold_font", false);
            mChargingOnly = obj.optBoolean("charging_only", false);
            mPaddingTopDp = (float) obj.optDouble("padding_top_dp", defPadTop);
            mPaddingLeftDp = (float) obj.optDouble("padding_left_dp", 0.0);
            mPaddingRightDp = (float) obj.optDouble("padding_right_dp", 2.0);
            mPowerSource = parsePowerSource(obj.optString("power_source", "live"));
            mViewStyleDirty = true;
            Log.i(TAG, "Config loaded: enabled=" + mEnabled + " dual=" + mDualLine
                    + " mode=" + mContentMode + " font=" + mFontSizeSp + " interval=" + mRefreshMs
                    + " padRight=" + mPaddingRightDp + " powerSource=" + mPowerSource);
        } catch (Throwable t) {
            Log.e(TAG, "Config parse failed", t);
        }
    }

    private static int parsePowerSource(String raw) {
        if (raw == null) {
            return POWER_LIVE;
        }
        final String v = raw.trim().toLowerCase(Locale.ROOT);
        if ("event".equals(v) || "sysfs_event".equals(v) || "event_io".equals(v)) {
            return POWER_EVENT;
        }
        if ("counter".equals(v) || "zero_io".equals(v)) {
            return POWER_COUNTER;
        }
        // "live", "sysfs_live", "auto", or anything else defaults to POWER_LIVE
        return POWER_LIVE;
    }

    private void prepareWorstCaseStrings() {
        final String temp = "35.0" + mTempUnit;
        final String watt = "1.50" + mPowerUnit;
        final String milli = "250mA";
        switch (mContentMode) {
            case 2:
                mWorstTop = watt;
                mWorstBottom = "";
                break;
            case 3:
                mWorstTop = temp;
                mWorstBottom = "";
                break;
            case 4:
                mWorstTop = temp;
                mWorstBottom = milli;
                break;
            case 5:
                mWorstTop = milli;
                mWorstBottom = watt;
                break;
            case 1:
            default:
                mWorstTop = temp;
                mWorstBottom = watt;
                break;
        }
        if (!mDualLine) {
            mWorstTop = mWorstBottom.isEmpty() ? mWorstTop : (mWorstTop + " " + mWorstBottom);
            mWorstBottom = "";
        }
    }

    // ================================================================== receivers

    private void registerReceivers() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);

        Intent sticky = null;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                sticky = mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                sticky = mContext.registerReceiver(mReceiver, filter);
            }
        } catch (Throwable t) {
            Log.e(TAG, "registerReceiver failed", t);
        }

        if (sticky != null) {
            onBatteryChanged(sticky, true);
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            final String action = intent.getAction();
            if (action == null) {
                return;
            }
            try {
                switch (action) {
                    case Intent.ACTION_BATTERY_CHANGED:
                        onBatteryChanged(intent, false);
                        break;
                    case Intent.ACTION_SCREEN_ON:
                    case Intent.ACTION_USER_PRESENT:
                        mScreenOn = true;
                        mAttachTries = 0;
                        scheduleAttach();
                        if (mEnabled && mPowerSource == POWER_LIVE) {
                            startSampling();
                        }
                        refresh();
                        break;
                    case Intent.ACTION_SCREEN_OFF:
                        mScreenOn = false;
                        stopSampling();
                        break;
                    case Intent.ACTION_CONFIGURATION_CHANGED:
                        mAttachTries = 0;
                        if (mView == null || !mView.isAttachedToWindow()) {
                            mView = null;
                            mClock = null;
                            scheduleAttach();
                        } else {
                            mViewStyleDirty = true;
                            refresh();
                        }
                        break;
                    default:
                        break;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Receiver error: " + t);
            }
        }
    };

    private void onBatteryChanged(Intent intent, boolean fromSticky) {
        mHaveBattery = true;
        mStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, mStatus);
        mPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, mPlugged);
        final int temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        if (temp != Integer.MIN_VALUE) {
            mTempTenths = temp;
        }
        mVoltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, mVoltageMv);

        final long now = SystemClock.elapsedRealtime();
        if (fromSticky || now - mLastBatteryLogMs > 30000L) {
            mLastBatteryLogMs = now;
            Log.d(TAG, "Battery (" + (fromSticky ? "sticky" : "live") + "): temp=" + mTempTenths
                    + " voltage=" + mVoltageMv + " status=" + mStatus + " plugged=" + mPlugged);
        }

        if (intent.hasExtra(EXTRA_CHARGE_COUNTER) && mPowerSource == POWER_COUNTER) {
            updateCounterCurrent(intent.getIntExtra(EXTRA_CHARGE_COUNTER, 0), fromSticky);
        }

        maybeCheckConfigFallback();

        if (mPowerSource == POWER_EVENT || (mPowerSource == POWER_LIVE && !mHaveCurrent)) {
            requestSysfsCurrent();
        }

        if (mScreenOn && mEnabled && mPowerSource == POWER_LIVE && !mSamplingActive) {
            startSampling();
        }

        refresh();
    }

    private void updateCounterCurrent(int counterUah, boolean fromSticky) {
        final long now = SystemClock.elapsedRealtime();
        if (!mHaveCounter || fromSticky) {
            mCounterUah = counterUah;
            mCounterAtMs = now;
            mHaveCounter = true;
            mCounterAnchorSticky = fromSticky;
            return;
        }
        if (mCounterAnchorSticky) {
            mCounterAnchorSticky = false;
            mCounterUah = counterUah;
            mCounterAtMs = now;
            return;
        }
        final long dtMs = now - mCounterAtMs;
        final long dq = counterUah - mCounterUah;
        if (Math.abs(dq) < COUNTER_MIN_DELTA_UAH || dtMs < 3000L) {
            return;
        }
        final double ma = Math.abs(dq) * 3600.0 / dtMs;
        if (ma >= 1.0 && ma < 20000.0) {
            mCurrentMa = mHaveCurrent ? (mCurrentMa * 0.35 + ma * 0.65) : ma;
            mHaveCurrent = true;
        }
        mCounterUah = counterUah;
        mCounterAtMs = now;
    }

    // ================================================================== sampling

    private final BattMonSampler.Callback mCurrentCallback = new BattMonSampler.Callback() {
        @Override
        public void onCurrent(double milliamps, boolean valid) {
            if (!mScreenOn || !mEnabled) {
                return;
            }
            if (valid && milliamps >= 1.0 && milliamps < 20000.0) {
                mCurrentMa = milliamps;
                mHaveCurrent = true;
                mSysfsFailures = 0;
                refresh();
            } else {
                mSysfsFailures++;
                if (mSysfsFailures >= 5 && mPowerSource == POWER_LIVE) {
                    Log.w(TAG, "Battery current reading unavailable via sysfs");
                }
            }
        }
    };

    private void requestSysfsCurrent() {
        mSampler.requestCurrent(mCurrentCallback);
    }

    private final Runnable mSampleTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mScreenOn || !mEnabled || mPowerSource != POWER_LIVE) {
                mSamplingActive = false;
                return;
            }
            if (mView == null || !mView.isAttachedToWindow()) {
                scheduleAttach();
                scheduleNextSampleTick();
                return;
            }
            if (mChargingOnly && !isCharging()) {
                hide();
                scheduleNextSampleTick();
                return;
            }

            requestSysfsCurrent();
            scheduleNextSampleTick();
        }
    };

    private void scheduleNextSampleTick() {
        if (!mScreenOn || !mEnabled || mPowerSource != POWER_LIVE) {
            mSamplingActive = false;
            return;
        }
        mSamplingActive = true;
        mMain.removeCallbacks(mSampleTickRunnable);
        final long delay = Math.max(250, mRefreshMs > 0 ? mRefreshMs : 1000);
        mMain.postDelayed(mSampleTickRunnable, delay);
    }

    private void startSampling() {
        if (!mScreenOn || !mEnabled || mPowerSource != POWER_LIVE) {
            return;
        }
        mSamplingActive = true;
        mMain.removeCallbacks(mSampleTickRunnable);
        requestSysfsCurrent();
        scheduleNextSampleTick();
    }

    private void stopSampling() {
        mSamplingActive = false;
        mMain.removeCallbacks(mSampleTickRunnable);
        mSampler.cancel();
    }

    // ================================================================== attach

    private final View.OnAttachStateChangeListener mAttachListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    mAttachTries = 0;
                    mViewStyleDirty = true;
                    if (mScreenOn && mEnabled && mPowerSource == POWER_LIVE) {
                        startSampling();
                    }
                    refresh();
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    mView = null;
                    mClock = null;
                    mAttachTries = 0;
                    scheduleAttach();
                }
            };

    private final Runnable mAttachRunnable = new Runnable() {
        @Override
        public void run() {
            mAttachPending = false;
            if (mView != null && mView.isAttachedToWindow()) {
                return;
            }
            if (tryAttach()) {
                mAttachTries = 0;
                if (mScreenOn && mEnabled && mPowerSource == POWER_LIVE) {
                    startSampling();
                }
                return;
            }
            mAttachTries++;
            scheduleAttach();
        }
    };

    private void scheduleAttach() {
        if (mAttachPending) {
            return;
        }
        if (mView != null && mView.isAttachedToWindow()) {
            return;
        }
        mAttachPending = true;
        final long delay = mAttachTries < 8
                ? Math.min(ATTACH_MAX_DELAY_MS, ATTACH_FIRST_DELAY_MS << mAttachTries)
                : 5000L;
        mMain.postDelayed(mAttachRunnable, delay);
    }

    private static boolean resolveReflection() {
        if (sReflectionReady) {
            return sWmgViewsField != null;
        }
        sReflectionReady = true;
        try {
            final Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            sGetWmgInstance = wmg.getMethod("getInstance");
            sWmgViewsField = wmg.getDeclaredField("mViews");
            sWmgViewsField.setAccessible(true);
        } catch (Throwable t) {
            Log.w(TAG, "WindowManagerGlobal reflection unavailable: " + t);
            sWmgViewsField = null;
        }
        return sWmgViewsField != null;
    }

    private boolean tryAttach() {
        if (mView != null && mView.isAttachedToWindow()) {
            return true;
        }
        if (!resolveReflection()) {
            return false;
        }
        try {
            final Object wmg = sGetWmgInstance.invoke(null);
            if (wmg == null) {
                return false;
            }
            final Object raw = sWmgViewsField.get(wmg);
            if (!(raw instanceof List)) {
                return false;
            }
            final List<?> live = (List<?>) raw;
            final ArrayList<View> roots = new ArrayList<>(live.size());
            for (Object o : live) {
                if (o instanceof View) {
                    roots.add((View) o);
                }
            }
            for (int i = 0; i < roots.size(); i++) {
                final TextView clock = findClockView(roots.get(i));
                if (clock == null) {
                    continue;
                }
                final ViewParent parent = clock.getParent();
                if (!(parent instanceof ViewGroup)) {
                    continue;
                }
                final ViewGroup group = (ViewGroup) parent;
                final View existing = group.findViewWithTag(BattMonView.VIEW_TAG);
                final BattMonView view;
                if (existing instanceof BattMonView) {
                    view = (BattMonView) existing;
                } else {
                    view = new BattMonView(group.getContext());
                    final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.gravity = Gravity.CENTER_VERTICAL;
                    final int clockIndex = group.indexOfChild(clock);
                    group.addView(view, clockIndex >= 0 ? clockIndex + 1 : 0, lp);
                    Log.i(TAG, "View attached next to clock at index " + (clockIndex + 1));
                }
                mView = view;
                mClock = clock;
                view.addOnAttachStateChangeListener(mAttachListener);
                mViewStyleDirty = true;
                refresh();
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Attach failed: " + t);
        }
        return false;
    }

    private TextView findClockView(View view) {
        final int[] budget = new int[]{MAX_SCAN_VIEWS};
        return findClockView(view, 0, budget);
    }

    private TextView findClockView(View view, int depth, int[] budget) {
        if (view == null || depth > MAX_SCAN_DEPTH || budget[0]-- <= 0) {
            return null;
        }
        if (view instanceof TextView) {
            final TextView tv = (TextView) view;
            final CharSequence text = tv.getText();
            if (isClockText(text)) {
                return tv;
            }
            final int id = tv.getId();
            if (id != View.NO_ID) {
                try {
                    final String name = tv.getResources().getResourceEntryName(id);
                    if (name != null && name.toLowerCase(Locale.ROOT).contains("clock")) {
                        return tv;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            final int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                final TextView found = findClockView(group.getChildAt(i), depth + 1, budget);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isClockText(CharSequence cs) {
        if (cs == null) {
            return false;
        }
        final String s = cs.toString().trim();
        final int len = s.length();
        if (len < 4 || len > 12) {
            return false;
        }
        return CLOCK_PATTERN.matcher(s).matches();
    }

    // ================================================================== rendering

    private void refresh() {
        try {
            if (!mEnabled) {
                hide();
                return;
            }
            if (mView == null || !mView.isAttachedToWindow()) {
                scheduleAttach();
                return;
            }
            if (mClock != null && mClock.getVisibility() != View.VISIBLE) {
                hide();
                return;
            }
            if (mChargingOnly && !isCharging()) {
                hide();
                return;
            }
            if (mView.getVisibility() != View.VISIBLE) {
                mView.setVisibility(View.VISIBLE);
            }

            buildLines();

            if (mViewStyleDirty) {
                mViewStyleDirty = false;
                mView.configure(mDualLine, mFontSizeSp, mBoldFont,
                        mClock != null ? mClock.getTypeface() : null,
                        mPaddingTopDp, mPaddingLeftDp, mPaddingRightDp, mWorstTop, mWorstBottom);
            }

            final int color = mClock != null ? mClock.getCurrentTextColor() : 0;
            if (mDualLine && mContentMode != 2 && mContentMode != 3) {
                mView.setContent(mLineTop, mLineBottom, color);
            } else {
                mView.setContent(mLineBottom.isEmpty() ? mLineTop
                        : (mLineTop + " " + mLineBottom), "", color);
            }
            mView.setContentDescription(mLineTop + " " + mLineBottom);

            final long now = SystemClock.elapsedRealtime();
            if (now - mLastLogMs > 5000L) {
                mLastLogMs = now;
                Log.d(TAG, "Display: " + mLineTop + " / " + mLineBottom
                        + " (" + (long) mCurrentMa + " mA, " + mVoltageMv + " mV, color="
                        + String.format("0x%08X", color) + ")");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Refresh failed: " + t);
        }
    }

    private void hide() {
        if (mView != null && mView.getVisibility() != View.GONE) {
            mView.setVisibility(View.GONE);
        }
    }

    private String mLineTop = "";
    private String mLineBottom = "";

    private void buildLines() {
        final double tempC = mTempTenths / 10.0;
        final double volts = mVoltageMv / 1000.0;
        final double amps = mHaveCurrent ? mCurrentMa / 1000.0 : 0.0;
        final double watts = volts * amps;

        switch (mContentMode) {
            case 2:
                mLineTop = formatWatts(watts);
                mLineBottom = "";
                break;
            case 3:
                mLineTop = formatTemp(tempC);
                mLineBottom = "";
                break;
            case 4:
                mLineTop = formatTemp(tempC);
                mLineBottom = formatMilliAmps();
                break;
            case 5:
                mLineTop = formatMilliAmps();
                mLineBottom = formatWatts(watts);
                break;
            case 1:
            default:
                mLineTop = formatTemp(tempC);
                mLineBottom = formatWatts(watts);
                break;
        }
    }

    private boolean isCharging() {
        if (mStatus == 2 /* CHARGING */ || mStatus == 5 /* FULL */) {
            return true;
        }
        return mPlugged != 0;
    }

    private String formatTemp(double tempC) {
        mSb.setLength(0);
        appendFixed(mSb, tempC, 1);
        mSb.append(mTempUnit);
        return mSb.toString();
    }

    private String formatWatts(double watts) {
        mSb.setLength(0);
        if (!mHaveCurrent) {
            mSb.append("--");
            mSb.append(mPowerUnit);
            return mSb.toString();
        }
        appendFixed(mSb, watts, 2);
        mSb.append(mPowerUnit);
        return mSb.toString();
    }

    private String formatMilliAmps() {
        mSb.setLength(0);
        if (!mHaveCurrent) {
            mSb.append("--mA");
            return mSb.toString();
        }
        mSb.append((long) (mCurrentMa + 0.5));
        mSb.append("mA");
        return mSb.toString();
    }

    private static void appendFixed(StringBuilder sb, double value, int decimals) {
        if (value < 0) {
            sb.append('-');
            value = -value;
        }
        long scale = 1;
        for (int i = 0; i < decimals; i++) {
            scale *= 10;
        }
        final long scaled = (long) (value * scale + 0.5);
        sb.append(scaled / scale);
        if (decimals > 0) {
            sb.append('.');
            long frac = scaled % scale;
            long div = scale / 10;
            while (div > 0) {
                sb.append((char) ('0' + (frac / div) % 10));
                div /= 10;
            }
        }
    }

    // ================================================================== dark icon tint

    private void initDarkIconDispatcher() {
        try {
            final Class<?> depClass = Class.forName("com.android.systemui.Dependency");
            final Class<?> dispatcherClass =
                    Class.forName("com.android.systemui.plugins.DarkIconDispatcher");
            final Object dispatcher = depClass.getMethod("get", Class.class)
                    .invoke(null, dispatcherClass);
            if (dispatcher == null) {
                return;
            }
            final Class<?> receiverClass =
                    Class.forName("com.android.systemui.plugins.DarkIconDispatcher$DarkReceiver");
            final Object proxy = Proxy.newProxyInstance(receiverClass.getClassLoader(),
                    new Class<?>[]{receiverClass}, (proxyObj, method, args) -> {
                        final String name = method.getName();
                        if ("onDarkChanged".equals(name) && args != null && args.length >= 3
                                && args[2] instanceof Integer) {
                            final int tint = (Integer) args[2];
                            if (mView != null && mView.isAttachedToWindow()) {
                                mView.setContent(mLineTop, mLineBottom, tint);
                            }
                        } else if ("equals".equals(name)) {
                            return proxyObj == args[0];
                        } else if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxyObj);
                        } else if ("toString".equals(name)) {
                            return "BattMonDarkReceiver";
                        }
                        return null;
                    });
            dispatcherClass.getMethod("addDarkReceiver", receiverClass).invoke(dispatcher, proxy);
        } catch (Throwable t) {
            Log.d(TAG, "Dark icon dispatcher hook unavailable (using clock color fallback): " + t.getMessage());
        }
    }

    // ================================================================== utils

    private static String readStringFile(File file) {
        final long len = file.length();
        if (len <= 0 || len > 65536) {
            return "";
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            final byte[] buf = new byte[(int) len];
            int read = 0;
            while (read < buf.length) {
                final int n = fis.read(buf, read, buf.length - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
            return new String(buf, 0, read).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
