package com.pocketzoom;

import android.util.Log;

import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Reads the Retroid "virtual mouse" device. The firmware exposes the pointer
 * position as EV_REL / REL_RX / REL_RY values that are really absolute screen
 * coordinates (0..width, 0..height); the node is world-readable, so a normal
 * app can follow the system cursor without root.
 *
 * <p>The reader only reports a position while the system "Mouse mode" is on
 * and the cursor is moving. When the mode is off the node stays silent.</p>
 */
final class MouseCursor implements Runnable {
    interface Listener {
        void onCursor(float x, float y);
    }

    private static final String TAG = "PocketZoom";
    private static final String DEVICE = "/dev/input/event8";
    /** struct input_event on 64-bit Android: timeval + type + code + value. */
    private static final int EVENT_SIZE = 24;
    private static final int EV_SYN = 0;
    private static final int EV_REL = 2;
    private static final int REL_RX = 3;
    private static final int REL_RY = 4;
    private static final long MIN_REPORT_MS = 25L;

    private final Listener listener;
    private volatile boolean running = true;
    private volatile InputStream stream;
    private Thread thread;

    private volatile float x = -1f;
    private volatile float y = -1f;
    private volatile int width;
    private volatile int height;
    private long lastLogAt;
    private long lastNotifyAt;

    MouseCursor(Listener listener) {
        this.listener = listener;
    }

    void setScreenSize(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    boolean hasPosition() {
        return x >= 0f && y >= 0f;
    }

    float getX() {
        return x;
    }

    float getY() {
        return y;
    }

    void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this, "pocketzoom-cursor");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        InputStream in = stream;
        if (in != null) {
            try {
                in.close();
            } catch (Throwable ignored) {
                // the reader thread may already have closed it
            }
        }
        thread = null;
    }

    @Override
    public void run() {
        while (running) {
            try {
                InputStream in = new FileInputStream(DEVICE);
                stream = in;
                Log.i(TAG, "cursor device opened");
                readLoop(in);
            } catch (Throwable t) {
                if (running) {
                    Log.w(TAG, "cursor device unavailable: " + t);
                }
            } finally {
                stream = null;
            }
            if (running) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void readLoop(InputStream in) throws Exception {
        byte[] buffer = new byte[EVENT_SIZE * 32];
        int carry = 0;
        int lastX = -1;
        int lastY = -1;

        while (running) {
            int read = in.read(buffer, carry, buffer.length - carry);
            if (read <= 0) {
                return;
            }
            int total = carry + read;
            int offset = 0;
            while (total - offset >= EVENT_SIZE) {
                int type = u16(buffer, offset + 16);
                int code = u16(buffer, offset + 18);
                int value = i32(buffer, offset + 20);

                if (type == EV_REL && code == REL_RX) {
                    lastX = value;
                } else if (type == EV_REL && code == REL_RY) {
                    lastY = value;
                } else if (type == EV_SYN && lastX >= 0 && lastY >= 0) {
                    // Keep the last value of the other axis: the firmware
                    // sometimes reports only one axis per report.
                    publish(lastX, lastY);
                }
                offset += EVENT_SIZE;
            }
            int leftover = total - offset;
            if (leftover > 0) {
                System.arraycopy(buffer, offset, buffer, 0, leftover);
            }
            carry = leftover;
        }
    }

    private void publish(int rawX, int rawY) {
        int w = width;
        int h = height;
        // The firmware reports the pointer in display pixels with the origin
        // in the bottom-left corner: x grows to the right, y grows upwards.
        // Observed on the Retroid Pocket 6: x 0..1920, y 0..1080 for the
        // landscape orientation. The magnification area starts below the
        // status bar, so scale the result into that (1920x1025) space.
        int mouseW = w > h ? 1920 : 1080;
        int mouseH = w > h ? 1080 : 1920;
        float px = rawX * (float) w / mouseW;
        float py = (mouseH - rawY) * (float) h / mouseH;
        if (w > 0) {
            px = Math.max(0f, Math.min(w - 1, px));
        }
        if (h > 0) {
            py = Math.max(0f, Math.min(h - 1, py));
        }
        x = px;
        y = py;
        long now = System.currentTimeMillis();
        if (now - lastLogAt >= 1000L) {
            lastLogAt = now;
            Log.i(TAG, "cursor " + Math.round(px) + "," + Math.round(py)
                    + " (raw " + rawX + "," + rawY + ")");
        }
        if (now - lastNotifyAt >= MIN_REPORT_MS) {
            lastNotifyAt = now;
            listener.onCursor(px, py);
        }
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int i32(byte[] b, int off) {
        return (b[off] & 0xff)
                | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 3] & 0xff) << 24);
    }
}
