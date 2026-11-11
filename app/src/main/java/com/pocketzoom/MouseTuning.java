package com.pocketzoom;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Pushes the cursor settings into the Retroid mapping service.
 *
 * <p>The firmware keeps the pointer speed and the "which stick is the pointer"
 * choice inside the same {@code GamepadConfig} that its "Cursor settings"
 * screen edits. That service is a system app, but its API service is exported
 * without a permission, so we can talk to it with a raw binder transaction. We
 * read the current config, patch only the sensitivity and the two joystick
 * names, and send the same config back. Nothing else is touched.</p>
 */
final class MouseTuning {
    private static final String TAG = "PocketZoom";
    private static final String PACKAGE = "com.rp.mapping";
    private static final String ACTION = "com.ro.mapping.action.MAPPING_SERVICE";
    private static final String INTERFACE = "com.ro.mapping.sdk.IServerApi";
    /** Transaction codes from the service's IServerApi stub. */
    private static final int TX_SET_CONFIG = 4;
    private static final int TX_GET_CONFIG = 5;
    /** The global "Mouse mode" mapping is saved under this id. */
    private static final String MOUSE_CONFIG_ID = "gamepadtomouse";
    private static final int TYPE_MOUSE_POINTER = 3001;
    private static final int TYPE_MOUSE_MOVE_TOUCHSCREEN = 3003;
    private static final String NAME_LEFT = "LEFT_JOYSTICK";
    private static final String NAME_RIGHT = "RIGHT_JOYSTICK";

    private MouseTuning() {
    }

    /** Applies the settings in the background; safe to call often. */
    static void apply(Context context, float sensitivity, boolean patchSensitivity,
                      boolean stickRight, boolean patchStick) {
        final Context app = context.getApplicationContext();
        final float value = Math.max(0.2f, Math.min(3.0f, sensitivity));
        Thread thread = new Thread(() -> {
            try {
                push(app, value, patchSensitivity, stickRight, patchStick);
            } catch (Throwable t) {
                Log.w(TAG, "cursor settings: failed", t);
            }
        }, "pocketzoom-cursor-settings");
        thread.setDaemon(true);
        thread.start();
    }

    private static void push(Context context, float value, boolean patchSensitivity,
                             boolean stickRight, boolean patchStick) {
        final Object lock = new Object();
        final IBinder[] binder = new IBinder[1];
        final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                synchronized (lock) {
                    binder[0] = service;
                    lock.notifyAll();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // the persistent service was restarted; nothing to do here
            }
        };

        boolean bound = false;
        try {
            bound = context.bindService(new Intent(ACTION).setPackage(PACKAGE),
                    connection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                Log.i(TAG, "cursor settings: mapping service unavailable");
                return;
            }
            IBinder service;
            synchronized (lock) {
                if (binder[0] == null) {
                    lock.wait(2000L);
                }
                service = binder[0];
            }
            if (service == null) {
                Log.i(TAG, "cursor settings: mapping service timeout");
                return;
            }
            send(service, value, patchSensitivity, stickRight, patchStick);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (bound) {
                try {
                    context.unbindService(connection);
                } catch (Throwable ignored) {
                    // already gone
                }
            }
        }
    }

    private static void send(IBinder service, float value, boolean patchSensitivity,
                             boolean stickRight, boolean patchStick) {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(INTERFACE);
            service.transact(TX_GET_CONFIG, request, reply, 0);
            reply.setDataPosition(0);
            reply.readException();
            int present = reply.readInt();
            if (present == 0) {
                Log.i(TAG, "cursor settings: no active config");
                return;
            }

            String id = reply.readString();
            skipMap(reply); // titles
            skipMap(reply); // descriptions
            reply.readByte();  // builtIn
            reply.readByte();  // readOnly
            reply.readInt();   // type
            reply.readFloat(); // alpha
            reply.readInt();   // toolbarLocation
            reply.readLong();  // createdTime
            reply.readLong();  // lastModifiedTime
            reply.readInt();   // version
            reply.readString(); // packageName

            int count = reply.readInt();
            if (count <= 0) {
                Log.i(TAG, "cursor settings: config '" + id + "' has no joysticks");
                return;
            }

            List<Stick> sticks = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                if (reply.readInt() == 0) {
                    continue;
                }
                Stick stick = new Stick();
                stick.namePosition = reply.dataPosition();
                stick.name = reply.readString();
                stick.type = reply.readInt();
                reply.readInt();    // touchCenter
                reply.readInt();    // touchRadius
                stick.sensitivityPosition = reply.dataPosition();
                reply.readInt();    // sensitivity
                reply.readByte();   // hideFromUi
                reply.readInt();    // speed
                reply.readInt();    // duration
                reply.readInt();    // mouseMode
                reply.readByte();   // reverseJoystick
                reply.readByte();   // reverseJoystick1
                reply.readInt();    // swipeData
                reply.readFloat();  // minEffectiveRadiusPercentage
                reply.readByte();   // followTouchIndicatorPosition
                sticks.add(stick);
            }

            if (!MOUSE_CONFIG_ID.equals(id)) {
                Log.i(TAG, "cursor settings: current config is '" + id + "', skipped");
                return;
            }
            if (sticks.isEmpty()) {
                return;
            }

            StringBuilder current = new StringBuilder();
            for (Stick stick : sticks) {
                current.append(stick.name).append('(').append(stick.type).append(") ");
            }
            Log.i(TAG, "cursor settings: current " + current.toString().trim());

            byte[] raw = reply.marshall();
            if (patchSensitivity) {
                int percent = Math.round(value * 100f);
                int packed = percent * 10000 + percent;
                for (Stick stick : sticks) {
                    putInt(raw, stick.sensitivityPosition, packed);
                }
            }
            if (patchStick) {
                raw = replaceStickNames(raw, sticks, stickRight);
            }

            Parcel patched = Parcel.obtain();
            Parcel setRequest = Parcel.obtain();
            Parcel setReply = Parcel.obtain();
            try {
                patched.unmarshall(raw, 0, raw.length);
                setRequest.writeInterfaceToken(INTERFACE);
                // The reply layout is: exception, present flag, config bytes.
                setRequest.appendFrom(patched, 4, raw.length - 4);
                service.transact(TX_SET_CONFIG, setRequest, setReply, 0);
                setReply.setDataPosition(0);
                setReply.readException();
                Log.i(TAG, "cursor settings applied: "
                        + (patchSensitivity ? "speed=" + value : "speed=system")
                        + ", stick=" + (patchStick ? (stickRight ? "right" : "left") : "system")
                        + ", joysticks=" + sticks.size());
            } finally {
                patched.recycle();
                setRequest.recycle();
                setReply.recycle();
            }
        } catch (Throwable t) {
            Log.w(TAG, "cursor settings: transaction failed", t);
        } finally {
            request.recycle();
            reply.recycle();
        }
    }

    private static void skipMap(Parcel parcel) {
        int size = parcel.readInt();
        if (size < 0) {
            return;
        }
        ClassLoader loader = MouseTuning.class.getClassLoader();
        for (int i = 0; i < size; i++) {
            parcel.readValue(loader);
            parcel.readValue(loader);
        }
    }

    private static void putInt(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private static int getInt(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    /**
     * Rebuilds the parcel with the two joystick names swapped. Parcel strings
     * include a 16-bit null terminator and are padded to 4 bytes, so
     * "LEFT_JOYSTICK" takes 32 bytes and "RIGHT_JOYSTICK" takes 36; the blocks
     * cannot be replaced in place. Both names are rewritten, which keeps the
     * total config size unchanged for a swap, while the rest of the bytes are
     * copied verbatim.
     */
    private static byte[] replaceStickNames(byte[] raw, List<Stick> sticks, boolean stickRight) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length + 8);
        int cursor = 0;
        for (Stick stick : sticks) {
            String target = stick.name;
            if (stick.type == TYPE_MOUSE_POINTER) {
                target = stickRight ? NAME_RIGHT : NAME_LEFT;
            } else if (stick.type == TYPE_MOUSE_MOVE_TOUCHSCREEN) {
                target = stickRight ? NAME_LEFT : NAME_RIGHT;
            }
            if (target == null) {
                target = "";
            }
            int namePosition = stick.namePosition;
            if (namePosition < cursor || namePosition > raw.length) {
                Log.w(TAG, "cursor settings: bad name position " + namePosition);
                return raw;
            }
            out.write(raw, cursor, namePosition - cursor);
            writeParcelString(out, target);
            cursor = namePosition + parcelStringSize(stick.name);
            if (cursor > raw.length) {
                Log.w(TAG, "cursor settings: bad name block");
                return raw;
            }
        }
        out.write(raw, cursor, raw.length - cursor);
        return out.toByteArray();
    }

    /** Size of a {@code Parcel.writeString} block, including its length field. */
    private static int parcelStringSize(String value) {
        int length = value == null ? 0 : value.length();
        return 4 + ((length * 2 + 2 + 3) & ~3);
    }

    private static void writeParcelString(java.io.ByteArrayOutputStream out, String value) {
        int length = value.length();
        out.write(length & 0xff);
        out.write((length >>> 8) & 0xff);
        out.write((length >>> 16) & 0xff);
        out.write((length >>> 24) & 0xff);
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            out.write(c & 0xff);
            out.write((c >>> 8) & 0xff);
        }
        out.write(0);
        out.write(0);
        int written = length * 2 + 2;
        int padded = (written + 3) & ~3;
        for (int i = written; i < padded; i++) {
            out.write(0);
        }
    }

    private static final class Stick {
        int namePosition;
        int sensitivityPosition;
        String name;
        int type;
    }
}
