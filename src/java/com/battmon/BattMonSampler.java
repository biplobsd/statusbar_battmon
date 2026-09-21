package com.battmon;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;

import java.io.FileInputStream;

/**
 * Opt-in, request-driven battery sysfs reader.
 *
 * <p>Only instantiated for {@code power_source = "sysfs_live" | "sysfs_event"}. It owns a
 * single background thread so that a battery property read - which on Qualcomm
 * {@code pmic_glink} hardware performs a synchronous RPC to the ADSP and blocks the caller
 * for ~1-4 ms - never runs on SystemUI's main thread.
 *
 * <p>The thread is created lazily on the first request and there is never more than one
 * request in flight, so the sampler cannot accumulate work.
 */
final class BattMonSampler {

    interface Callback {
        /**
         * @param milliamps absolute current in mA
         * @param valid     false when the property could not be read
         */
        void onCurrent(double milliamps, boolean valid);
    }

    private static final String SYS_CURRENT = "/sys/class/power_supply/battery/current_now";

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.atomic.AtomicInteger mGeneration =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private HandlerThread mThread;
    private Handler mHandler;
    private boolean mBusy;

    void requestCurrent(final Callback callback) {
        final Handler handler = ensureThread();
        if (handler == null || mBusy) {
            return;
        }
        mBusy = true;
        final int gen = mGeneration.get();
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (gen != mGeneration.get()) {
                    mBusy = false;
                    return;
                }
                final long raw = readLong(SYS_CURRENT);
                final boolean valid = raw != Long.MIN_VALUE;
                final double milliamps = valid ? Math.abs(raw) / 1000.0 : 0.0;
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != mGeneration.get()) {
                            mBusy = false;
                            return;
                        }
                        mBusy = false;
                        callback.onCurrent(milliamps, valid);
                    }
                });
            }
        });
    }

    void cancel() {
        mGeneration.incrementAndGet();
        mBusy = false;
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }
    }

    private Handler ensureThread() {
        if (mHandler != null) {
            return mHandler;
        }
        try {
            mThread = new HandlerThread("BattMonSampler", Process.THREAD_PRIORITY_BACKGROUND);
            mThread.start();
            mHandler = new Handler(mThread.getLooper());
            return mHandler;
        } catch (Throwable t) {
            mThread = null;
            mHandler = null;
            return null;
        }
    }

    private static long readLong(String path) {
        try (FileInputStream fis = new FileInputStream(path)) {
            final byte[] buf = new byte[32];
            final int read = fis.read(buf);
            if (read <= 0) {
                return Long.MIN_VALUE;
            }
            long value = 0;
            boolean any = false;
            boolean negative = false;
            for (int i = 0; i < read; i++) {
                final char c = (char) buf[i];
                if (c == '-') {
                    negative = true;
                } else if (c >= '0' && c <= '9') {
                    value = value * 10 + (c - '0');
                    any = true;
                } else if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                    if (any) {
                        break;
                    }
                } else {
                    return Long.MIN_VALUE;
                }
            }
            if (!any) {
                return Long.MIN_VALUE;
            }
            return negative ? -value : value;
        } catch (Throwable ignored) {
            return Long.MIN_VALUE;
        }
    }
}
