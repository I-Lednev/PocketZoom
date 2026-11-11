package com.pocketzoom;

import android.view.KeyEvent;

/** Human readable names for the key codes we care about. */
public final class KeyNames {
    private KeyNames() {
    }

    public static String label(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return "A";
            case KeyEvent.KEYCODE_BUTTON_B:
                return "B";
            case KeyEvent.KEYCODE_BUTTON_X:
                return "X";
            case KeyEvent.KEYCODE_BUTTON_Y:
                return "Y";
            case KeyEvent.KEYCODE_BUTTON_L1:
                return "L1";
            case KeyEvent.KEYCODE_BUTTON_R1:
                return "R1";
            case KeyEvent.KEYCODE_BUTTON_L2:
                return "L2";
            case KeyEvent.KEYCODE_BUTTON_R2:
                return "R2";
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return "L3";
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return "R3";
            case KeyEvent.KEYCODE_BUTTON_START:
                return "Start";
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return "Select";
            case KeyEvent.KEYCODE_BUTTON_MODE:
                return "Mode";
            case KeyEvent.KEYCODE_VOLUME_UP:
                return "Громкость +";
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                return "Громкость −";
            case KeyEvent.KEYCODE_BACK:
                return "Назад";
            case KeyEvent.KEYCODE_HOME:
                return "Домой";
            case KeyEvent.KEYCODE_MENU:
                return "Меню";
            case KeyEvent.KEYCODE_ENTER:
                return "Enter";
            case KeyEvent.KEYCODE_SPACE:
                return "Пробел";
            default:
                break;
        }
        String name = KeyEvent.keyCodeToString(code);
        if (name.startsWith("KEYCODE_")) {
            name = name.substring("KEYCODE_".length());
        }
        return name;
    }
}
