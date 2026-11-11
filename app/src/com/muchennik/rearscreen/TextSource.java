package com.muchennik.rearscreen;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import io.nayuki.qrcodegen.QrCode;

/** Ticker (running text) and QR code for the rear screen. Settings are read every frame. */
final class TextSource {
    static final String[] COLORS = {"Оранжевый", "Белый", "Красный", "Зелёный", "Голубой", "Жёлтый", "Радуга"};
    private static final int[] COLOR_VALUES = {0xFFFF7A3D, 0xFFFFFFFF, 0xFFFF3B3B, 0xFF3BFF6E, 0xFF3BC8FF, 0xFFFFD83B, 0};
    static final String[] SIZES = {"Огромный", "Крупный", "Средний"};
    private static final float[] SIZE_PX = {190, 130, 84};
    static final String[] TICKER_STYLES = {"Бегущая строка", "Статично, по центру"};
    static final String[] QR_TYPES = {"Текст или ссылка", "Wi-Fi", "Контакт"};

    private final Bitmap bmp = Bitmap.createBitmap(Frames.W, Frames.H, Bitmap.Config.ARGB_8888);
    private final Canvas c = new Canvas(bmp);
    private final TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private float x = Frames.W;
    private long t0 = System.currentTimeMillis();

    // QR cache
    private String qrKey;
    private QrCode qr;
    private String qrError;

    TextSource() {
        p.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
    }

    void ticker(Prefs pr, byte[] frame) {
        String text = pr.tickerText();
        if (text.isEmpty()) text = "Привет 👋";
        int ci = pr.tickerColor();
        float size = SIZE_PX[Math.max(0, Math.min(SIZE_PX.length - 1, pr.tickerSize()))];
        c.drawColor(Color.BLACK);
        p.setShader(null);
        p.setColor(ci < COLOR_VALUES.length && COLOR_VALUES[ci] != 0 ? COLOR_VALUES[ci] : Color.WHITE);
        if (pr.tickerStyle() == 1) { // static, wrapped, shrunk until it fits
            float s = size;
            StaticLayout l;
            do {
                p.setTextSize(s);
                l = StaticLayout.Builder.obtain(text, 0, text.length(), p, Frames.W - 16)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0, 0.9f).build();
                s -= 4;
            } while ((l.getHeight() > Frames.H - 16 || widest(l) > Frames.W - 16) && s > 12);
            if (ci == COLOR_VALUES.length - 1) rainbow(0);
            c.save();
            c.translate(8, (Frames.H - l.getHeight()) / 2f);
            l.draw(c);
            c.restore();
        } else {
            p.setTextSize(size);
            float w = p.measureText(text);
            float speed = 2 + pr.tickerSpeed() * 1.2f;   // px per frame (~18 fps panel)
            x -= speed;
            if (x < -w) x = Frames.W;
            if (ci == COLOR_VALUES.length - 1) rainbow(x);
            Paint.FontMetrics fm = p.getFontMetrics();
            float y = Frames.H / 2f - (fm.ascent + fm.descent) / 2;
            c.drawText(text, x, y, p);
        }
        Frames.fromBitmap(bmp, frame);
    }

    private static float widest(StaticLayout l) {
        float m = 0;
        for (int i = 0; i < l.getLineCount(); i++) m = Math.max(m, l.getLineWidth(i));
        return m;
    }

    private void rainbow(float offset) {
        float shift = (System.currentTimeMillis() - t0) / 8f;
        int[] cols = new int[7];
        for (int i = 0; i < cols.length; i++) cols[i] = Color.HSVToColor(new float[] {(i * 60 + shift) % 360, 0.9f, 1});
        p.setShader(new LinearGradient(offset, 0, offset + 600, 0, cols, null, Shader.TileMode.MIRROR));
    }

    /** What the QR code encodes for the current settings. */
    static String qrPayload(Prefs pr) {
        switch (pr.qrType()) {
            case 1: {
                String sec = pr.qrWifiPass().isEmpty() ? "nopass" : "WPA";
                return "WIFI:T:" + sec + ";S:" + esc(pr.qrWifiSsid()) + ";"
                        + (pr.qrWifiPass().isEmpty() ? "" : "P:" + esc(pr.qrWifiPass()) + ";") + ";";
            }
            case 2:
                return "MECARD:N:" + esc(pr.qrName()) + ";TEL:" + esc(pr.qrPhone()) + ";;";
            default:
                return pr.qrText();
        }
    }

    private static String esc(String s) {
        String bs = String.valueOf('\\');
        return s.replace(bs, bs + bs).replace(";", bs + ";").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"");
    }

    static String qrCaption(Prefs pr) {
        switch (pr.qrType()) {
            case 1: return "Wi-Fi: " + pr.qrWifiSsid();
            case 2: return pr.qrName();
            default: return pr.qrCaption();
        }
    }

    void qr(Prefs pr, byte[] frame) {
        String payload = qrPayload(pr);
        String caption = qrCaption(pr);
        if (!payload.equals(qrKey)) {
            qrKey = payload;
            qr = null;
            qrError = null;
            if (payload.isEmpty() || payload.equals("WIFI:T:nopass;S:;;") || payload.startsWith("MECARD:N:;TEL:;")) qrError = "Заполни данные\nв приложении";
            else try {
                qr = QrCode.encodeText(payload, QrCode.Ecc.MEDIUM);
            } catch (Exception e) {
                qrError = "Слишком длинно\nдля QR";
            }
        }
        boolean cap = caption != null && !caption.isEmpty();
        c.drawColor(Color.WHITE);
        p.setShader(null);
        if (qr == null) {
            c.drawColor(Color.BLACK);
            p.setColor(0xFFFF7A3D);
            p.setTextSize(18);
            p.setTextAlign(Paint.Align.CENTER);
            String[] ls = qrError.split("\n");
            for (int i = 0; i < ls.length; i++) c.drawText(ls[i], Frames.W / 2f, Frames.H / 2f + (i - (ls.length - 1) / 2f) * 24, p);
            p.setTextAlign(Paint.Align.LEFT);
        } else {
            int n = qr.size + 4;                                  // with a 2-module quiet zone
            int avail = cap ? Frames.H - 50 : Frames.H;
            int box = Math.min(Frames.W - 20, avail - 10); // keep the finder squares out of the rounded corners
            int m = Math.max(1, box / n);                         // whole pixels per module = crisp
            int sz = m * n;
            int ox = (Frames.W - sz) / 2, oy = (avail - sz) / 2;
            p.setColor(Color.BLACK);
            for (int y = 0; y < qr.size; y++)
                for (int xx = 0; xx < qr.size; xx++)
                    if (qr.getModule(xx, y)) c.drawRect(ox + (xx + 2) * m, oy + (y + 2) * m, ox + (xx + 3) * m, oy + (y + 3) * m, p);
            if (cap) {
                p.setColor(Color.BLACK);
                p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                float s = 20;
                p.setTextSize(s);
                while (p.measureText(caption) > Frames.W - 40 && s > 10) p.setTextSize(s -= 1);
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText(caption, Frames.W / 2f, Frames.H - 22, p);
                p.setTextAlign(Paint.Align.LEFT);
                p.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
            }
        }
        Frames.fromBitmap(bmp, frame);
    }
}
