package com.muchennik.rearscreen;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;

/** App grid drawn on the rear screen itself: icons only, 3x3 per page, swipe left/right for pages. */
final class Launcher {
    static final int COLS = 3, ROWS = 3, PER_PAGE = COLS * ROWS, ICON = 64, CELL_W = Frames.W / COLS, CELL_H = 94;
    private final List<String> pkgs = new ArrayList<>();
    private final List<Bitmap> icons = new ArrayList<>();
    private final Bitmap canvasBmp = Bitmap.createBitmap(Frames.W, Frames.H, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(canvasBmp);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private int page, pressed = -1;
    private int downX, downY;
    volatile boolean dirty = true;

    Launcher(Context c, List<String> packages) {
        PackageManager pm = c.getPackageManager();
        for (String p : packages) {
            try {
                Drawable d = pm.getApplicationIcon(p);
                Bitmap b = Bitmap.createBitmap(ICON, ICON, Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, ICON, ICON);
                d.draw(new Canvas(b));
                pkgs.add(p);
                icons.add(b);
            } catch (Exception ignored) { } // uninstalled
        }
    }

    private int pages() { return Math.max(1, (pkgs.size() + PER_PAGE - 1) / PER_PAGE); }

    void render(byte[] frame) {
        canvas.drawColor(Color.BLACK);
        int top = (Frames.H - 10 - ROWS * CELL_H) / 2;
        if (pkgs.isEmpty()) {
            paint.setColor(Color.GRAY);
            paint.setTextSize(16);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Добавь приложения", Frames.W / 2f, Frames.H / 2f - 10, paint);
            canvas.drawText("на основном экране", Frames.W / 2f, Frames.H / 2f + 12, paint);
        }
        for (int k = 0; k < PER_PAGE; k++) {
            int i = page * PER_PAGE + k;
            if (i >= pkgs.size()) break;
            int cx = (k % COLS) * CELL_W, cy = top + (k / COLS) * CELL_H;
            float l = cx + (CELL_W - ICON) / 2f, t = cy + (CELL_H - ICON) / 2f;
            if (i == pressed) {
                paint.setColor(0xFF404040);
                canvas.drawRoundRect(new RectF(l - 8, t - 8, l + ICON + 8, t + ICON + 8), 14, 14, paint);
            }
            canvas.drawBitmap(icons.get(i), l, t, paint);
        }
        int n = pages();
        if (n > 1) for (int p = 0; p < n; p++) {
            paint.setColor(p == page ? Color.WHITE : 0xFF555555);
            canvas.drawCircle(Frames.W / 2f + (p - (n - 1) / 2f) * 14, Frames.H - 8, 3.5f, paint);
        }
        Frames.fromBitmap(canvasBmp, frame);
        dirty = false;
    }

    private int cellAt(int x, int y) {
        int top = (Frames.H - 10 - ROWS * CELL_H) / 2;
        int col = x / CELL_W, row = (y - top) / CELL_H;
        if (y < top || col < 0 || col >= COLS || row < 0 || row >= ROWS) return -1;
        int i = page * PER_PAGE + row * COLS + col;
        return i < pkgs.size() ? i : -1;
    }

    /** Rear touch line ("D x y" / "M x y" / "U x y"); returns the package to open on a tap. */
    String touch(String line) {
        String[] s = line.split(" ");
        int x = Integer.parseInt(s[1]), y = Integer.parseInt(s[2]);
        switch (s[0]) {
            case "D":
                downX = x; downY = y;
                pressed = cellAt(x, y);
                dirty = true;
                return null;
            case "M":
                if (pressed >= 0 && (Math.abs(x - downX) > 20 || Math.abs(y - downY) > 20)) { pressed = -1; dirty = true; }
                return null;
            default: // U
                int dx = x - downX;
                String open = null;
                if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(y - downY)) {
                    page = Math.max(0, Math.min(pages() - 1, page + (dx < 0 ? 1 : -1)));
                } else if (pressed >= 0 && pressed == cellAt(x, y)) {
                    open = pkgs.get(pressed);
                }
                pressed = -1;
                dirty = true;
                return open;
        }
    }
}
