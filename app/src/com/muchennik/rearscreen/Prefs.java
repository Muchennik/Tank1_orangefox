package com.muchennik.rearscreen;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** All settings of the app. */
final class Prefs {
    static final int FIT_FILL = 0, FIT_FIT = 1, FIT_STRETCH = 2;
    /** when the picture goes out */
    static final int OFF_TIMER = 0, OFF_SCREEN = 1, OFF_DOUBLE_TAP = 2, OFF_NEVER = 3;

    final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("rear", Context.MODE_PRIVATE);
    }

    List<String> media() {
        List<String> l = new ArrayList<>();
        String s = sp.getString("media", "");
        for (String u : s.split("\n")) if (!u.isEmpty()) l.add(u);
        return l;
    }

    void setMedia(List<String> l) {
        sp.edit().putString("media", String.join("\n", l)).apply();
    }

    int fit() { return sp.getInt("fit", FIT_FILL); }
    int offMode() { return sp.getInt("off", OFF_SCREEN); }
    int timerSec() { return sp.getInt("timer", 30); }
    int slideSec() { return sp.getInt("slide", 10); }
    int brightness() { return sp.getInt("bl", 12); }
    boolean wakeOnDoubleTap() { return sp.getBoolean("wake_dt", true); }
    boolean running() { return sp.getBoolean("running", false); }

    void put(String k, int v) { sp.edit().putInt(k, v).apply(); }
    void put(String k, boolean v) { sp.edit().putBoolean(k, v).apply(); }
}
