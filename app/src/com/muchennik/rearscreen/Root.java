package com.muchennik.rearscreen;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** su helpers and the native rearhelper binary (shipped in assets). */
final class Root {
    static final String TAG = "RearScreen";

    private Root() {}

    /** Runs a shell command as root and waits; returns the exit code (-1 on failure). */
    static int run(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[] {"su", "-c", cmd});
            return p.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "su " + cmd + ": " + e);
            return -1;
        }
    }

    static boolean available() {
        return run("id") == 0;
    }

    /** Copies assets/rearhelper to the app files dir (once per app version) and returns its path. */
    static String helper(Context c) throws Exception {
        File f = new File(c.getFilesDir(), "rearhelper");
        long ver = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        File stamp = new File(c.getFilesDir(), "rearhelper.ver");
        boolean fresh = f.exists() && stamp.exists()
                && new String(java.nio.file.Files.readAllBytes(stamp.toPath())).equals(String.valueOf(ver));
        if (!fresh) {
            try (InputStream in = c.getAssets().open("rearhelper");
                 OutputStream out = new FileOutputStream(f)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            f.setExecutable(true, false);
            f.setReadable(true, false);
            try (OutputStream out = new FileOutputStream(stamp)) {
                out.write(String.valueOf(ver).getBytes());
            }
        }
        return f.getAbsolutePath();
    }

    /** Blackview's own rear-screen UI in DKSystemUI on/off (Settings.Global mini_screen_switch). */
    static void blackviewUi(boolean on) {
        run("settings put global mini_screen_switch " + (on ? 1 : 0));
    }
}
