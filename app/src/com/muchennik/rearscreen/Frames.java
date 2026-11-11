package com.muchennik.rearscreen;

import android.graphics.Bitmap;
import android.graphics.RectF;

/** Rear display geometry and RGB565 (big-endian) frame helpers. */
final class Frames {
    static final int W = 240, H = 296, SIZE = W * H * 2;

    private Frames() {}

    private static final int[] PIX = new int[W * H];

    /** Bitmap (W x H, ARGB_8888) -> RGB565 big-endian, the format DKSystemUI sends. */
    static synchronized void fromBitmap(Bitmap b, byte[] out) {
        b.getPixels(PIX, 0, W, 0, 0, W, H);
        for (int i = 0, j = 0; i < PIX.length; i++, j += 2) {
            int p = PIX[i];
            int s = ((p >> 8) & 0xF800) | ((p >> 5) & 0x07E0) | ((p >> 3) & 0x001F);
            out[j] = (byte) (s >> 8);
            out[j + 1] = (byte) s;
        }
    }

    static void put(byte[] out, int i, int r, int g, int b) {
        int s = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);
        out[2 * i] = (byte) (s >> 8);
        out[2 * i + 1] = (byte) s;
    }

    /** Where a srcW x srcH picture goes on the rear display for the given fit mode. */
    static RectF dest(int srcW, int srcH, int fit) {
        if (fit == Prefs.FIT_STRETCH || srcW <= 0 || srcH <= 0) return new RectF(0, 0, W, H);
        float s = fit == Prefs.FIT_FILL
                ? Math.max((float) W / srcW, (float) H / srcH)
                : Math.min((float) W / srcW, (float) H / srcH);
        float w = srcW * s, h = srcH * s;
        float l = (W - w) / 2f, t = (H - h) / 2f;
        return new RectF(l, t, l + w, t + h);
    }
}
