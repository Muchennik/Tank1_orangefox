package com.muchennik.rearscreen;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * System monitor for the rear screen: CPU load / temperature / core clocks, RAM, GPU,
 * battery, storage, clock. Values are read through one long-lived root shell (apps may not
 * read /proc/stat or most thermal zones on Android 16).
 */
final class StatsSource {
    private static final int ACC = 0xFFFF7A3D, TXT = 0xFFF1F2F5, SUB = 0xFF8A90A0, TRACK = 0xFF2A2E37, CARD = 0xFF15171C;
    private static final String END = "__END__";
    private static final String SCRIPT =
            "head -n 9 /proc/stat; grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo; "
            + "for z in /sys/class/thermal/thermal_zone*; do echo \"T $(cat $z/type 2>/dev/null) $(cat $z/temp 2>/dev/null)\"; done; "
            + "for c in 0 1 2 3 4 5 6 7; do echo \"F $c $(cat /sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq 2>/dev/null) "
            + "$(cat /sys/devices/system/cpu/cpu$c/cpufreq/cpuinfo_max_freq 2>/dev/null)\"; done; "
            + "echo \"G $(cat /sys/kernel/ged/hal/gpu_utilization 2>/dev/null)\"; "
            + "echo \"GF $(cat /sys/kernel/ged/hal/current_freqency 2>/dev/null)\"; "
            + "echo " + END;

    private final Context ctx;
    private final Process sh;
    private final OutputStream shIn;
    private final BufferedReader shOut;
    private final long[] prevTotal = new long[9], prevIdle = new long[9];
    private float cpu;                    // 0..1
    private final float[] core = new float[8];
    private final int[] freq = new int[8], fmax = new int[8];
    private final List<Float> cpuHist = new ArrayList<>();
    private long memTotal, memAvail, swapTotal, swapFree; // kB
    private float cpuTemp = Float.NaN, gpuTemp = Float.NaN;
    private int gpuLoad = -1, gpuFreq = -1;
    private final Bitmap bmp = Bitmap.createBitmap(Frames.W, Frames.H, Bitmap.Config.ARGB_8888);
    private final Canvas c = new Canvas(bmp);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface med = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface reg = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface cond = Typeface.create("sans-serif-condensed", Typeface.NORMAL);

    StatsSource(Context ctx) throws Exception {
        this.ctx = ctx;
        sh = Runtime.getRuntime().exec(new String[] {"su"});
        shIn = sh.getOutputStream();
        shOut = new BufferedReader(new InputStreamReader(sh.getInputStream()));
        sample();
    }

    private void sample() throws Exception {
        shIn.write((SCRIPT + "\n").getBytes());
        shIn.flush();
        float ct = Float.NaN, gt = Float.NaN;
        String l;
        while ((l = shOut.readLine()) != null && !l.equals(END)) {
            String[] s = l.trim().split("\\s+");
            if (s.length == 0) continue;
            try {
                if (s[0].startsWith("cpu") && s.length >= 8) {
                    int idx = s[0].equals("cpu") ? 0 : 1 + Integer.parseInt(s[0].substring(3));
                    if (idx > 8) continue;
                    long total = 0;
                    for (int k = 1; k < s.length; k++) total += Long.parseLong(s[k]);
                    long idle = Long.parseLong(s[4]) + Long.parseLong(s[5]);
                    long dt = total - prevTotal[idx], di = idle - prevIdle[idx];
                    float load = dt > 0 ? Math.max(0, Math.min(1, 1f - di / (float) dt)) : 0;
                    if (idx == 0) cpu = load; else core[idx - 1] = load;
                    prevTotal[idx] = total;
                    prevIdle[idx] = idle;
                } else if (s[0].equals("MemTotal:")) memTotal = Long.parseLong(s[1]);
                else if (s[0].equals("MemAvailable:")) memAvail = Long.parseLong(s[1]);
                else if (s[0].equals("SwapTotal:")) swapTotal = Long.parseLong(s[1]);
                else if (s[0].equals("SwapFree:")) swapFree = Long.parseLong(s[1]);
                else if (s[0].equals("T") && s.length >= 3) {
                    String type = s[1].toLowerCase(Locale.ROOT);
                    float t = Integer.parseInt(s[2]) / 1000f;
                    if (t < 5 || t > 150) continue;
                    if (type.contains("cpu") || type.contains("soc_max")) ct = Float.isNaN(ct) ? t : Math.max(ct, t);
                    if (type.contains("gpu")) gt = Float.isNaN(gt) ? t : Math.max(gt, t);
                } else if (s[0].equals("F") && s.length >= 4) {
                    int i = Integer.parseInt(s[1]);
                    freq[i] = Integer.parseInt(s[2]) / 1000;
                    fmax[i] = Integer.parseInt(s[3]) / 1000;
                } else if (s[0].equals("F") && s.length >= 2) {
                    freq[Integer.parseInt(s[1])] = 0; // core offline
                } else if (s[0].equals("G")) {
                    gpuLoad = s.length > 1 ? Integer.parseInt(s[1]) : -1;
                } else if (s[0].equals("GF")) {
                    gpuFreq = s.length > 1 ? Integer.parseInt(s[1]) / 1000 : -1;
                }
            } catch (Exception ignored) { } // odd line
        }
        cpuTemp = ct;
        gpuTemp = gt;
        cpuHist.add(cpu);
        while (cpuHist.size() > 60) cpuHist.remove(0);
    }

    void render(byte[] frame) {
        try { sample(); } catch (Exception e) { Log.w(Root.TAG, "stats: " + e); }
        Intent bat = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = bat != null ? bat.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / Math.max(1, bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100)) : -1;
        float batT = bat != null ? bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f : 0;
        boolean charging = bat != null && bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        int mA = 0;
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            int ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            mA = Math.abs(ua) > 20000 ? ua / 1000 : ua; // some kernels report mA, most µA
        } catch (Exception ignored) {}

        c.drawColor(Color.BLACK);
        // ---- header: clock + battery
        Date now = new Date();
        text(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), 10, 34, 32, TXT, med, Paint.Align.LEFT);
        text(new SimpleDateFormat("EE, d MMM", new Locale("ru")).format(now), 12, 50, 12, SUB, reg, Paint.Align.LEFT);
        text(level + "%", 229, 28, 18, level <= 15 ? 0xFFFF4D4D : TXT, med, Paint.Align.RIGHT);
        text((charging ? "⚡ " : "") + fmt1(batT) + "° · " + mA + " мА", 229, 46, 11, SUB, cond, Paint.Align.RIGHT);

        // ---- CPU card: ring + temps + history + cores
        card(6, 58, 234, 216);
        float ringCx = 46, ringCy = 104, ringR = 31;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(8);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(TRACK);
        RectF ring = new RectF(ringCx - ringR, ringCy - ringR, ringCx + ringR, ringCy + ringR);
        c.drawArc(ring, 135, 270, false, p);
        p.setColor(loadColor(cpu));
        c.drawArc(ring, 135, Math.max(2, 270 * cpu), false, p);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
        text(Math.round(cpu * 100) + "%", ringCx, ringCy + 6, 17, TXT, med, Paint.Align.CENTER);
        text("ЦП", ringCx, ringCy + 30, 10, SUB, med, Paint.Align.CENTER);
        text(Float.isNaN(cpuTemp) ? "—" : fmt1(cpuTemp) + "°", 90, 90, 22, tempColor(cpuTemp), med, Paint.Align.LEFT);
        text("темп. ЦП", 91, 104, 10, SUB, reg, Paint.Align.LEFT);
        String gpu = (gpuLoad >= 0 ? gpuLoad + "%" : "—") + (Float.isNaN(gpuTemp) ? "" : "  " + fmt1(gpuTemp) + "°");
        text(gpu, 160, 90, 15, TXT, med, Paint.Align.LEFT);
        text("ГП" + (gpuFreq > 0 ? " · " + gpuFreq + " МГц" : ""), 161, 104, 10, SUB, reg, Paint.Align.LEFT);
        float hx = 90, hy = 112, hw = 138, hh = 22;
        p.setColor(0xFF1E2128);
        c.drawRect(hx, hy, hx + hw, hy + hh, p);
        if (cpuHist.size() > 1) {
            Path path = new Path();
            for (int i = 0; i < cpuHist.size(); i++) {
                float x = hx + hw * i / 59f, y = hy + hh - cpuHist.get(i) * hh;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
            p.setColor(ACC);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
        }
        float bx = 14, bw = 27.5f, by = 150, bh = 46;
        for (int i = 0; i < 8; i++) {
            float x = bx + i * bw;
            p.setColor(TRACK);
            c.drawRoundRect(new RectF(x, by, x + bw - 5, by + bh), 3, 3, p);
            float hgt = Math.max(2, core[i] * bh);
            p.setColor(loadColor(core[i]));
            c.drawRoundRect(new RectF(x, by + bh - hgt, x + bw - 5, by + bh), 3, 3, p);
            float f = fmax[i] > 0 ? Math.min(1, freq[i] / (float) fmax[i]) : 0;
            String fq = freq[i] <= 0 ? "off" : freq[i] >= 1000 ? fmt1(freq[i] / 1000f) : String.valueOf(freq[i]);
            text(fq, x + (bw - 5) / 2, by + bh + 12, 9, f > 0.85f ? ACC : SUB, cond, Paint.Align.CENTER);
        }

        // ---- RAM card
        card(6, 222, 234, 264);
        long used = memTotal - memAvail;
        float ru = memTotal > 0 ? used / (float) memTotal : 0;
        text("ОЗУ", 14, 238, 12, SUB, med, Paint.Align.LEFT);
        text(gb(used) + " / " + gb(memTotal) + " ГБ", 226, 238, 13, TXT, med, Paint.Align.RIGHT);
        bar(14, 245, 212, 7, ru, loadColor(ru));
        text("свободно " + gb(memAvail) + " ГБ" + (swapTotal > 0 ? " · своп " + gb(swapTotal - swapFree) + " ГБ" : ""),
                14, 260, 10, SUB, reg, Paint.Align.LEFT);

        // ---- storage line
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            long free = fs.getAvailableBytes() >> 10, tot = fs.getTotalBytes() >> 10;
            text("Память", 14, 284, 11, SUB, med, Paint.Align.LEFT);
            bar(62, 278, 106, 6, 1 - free / (float) tot, 0xFF6C7282);
            text("своб. " + Math.round(free / 1048576f) + " ГБ", 228, 285, 11, TXT, cond, Paint.Align.RIGHT);
        } catch (Exception ignored) {}

        Frames.fromBitmap(bmp, frame);
    }

    private void card(float l, float t, float r, float b) {
        p.setColor(CARD);
        c.drawRoundRect(new RectF(l, t, r, b), 10, 10, p);
    }

    private void bar(float x, float y, float w, float h, float v, int color) {
        p.setColor(TRACK);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), h / 2, h / 2, p);
        p.setColor(color);
        c.drawRoundRect(new RectF(x, y, x + Math.max(h, w * Math.max(0, Math.min(1, v))), y + h), h / 2, h / 2, p);
    }

    private void text(String s, float x, float y, float size, int color, Typeface tf, Paint.Align a) {
        p.setTextSize(size);
        p.setColor(color);
        p.setTypeface(tf);
        p.setTextAlign(a);
        c.drawText(s, x, y, p);
    }

    private static int loadColor(float v) {
        return v < 0.5f ? ACC : v < 0.8f ? 0xFFFF9F43 : 0xFFFF4D4D;
    }

    private static int tempColor(float t) {
        return Float.isNaN(t) ? SUB : t < 45 ? TXT : t < 60 ? 0xFFFF9F43 : 0xFFFF4D4D;
    }

    private static String fmt1(float v) { return String.format(Locale.US, "%.1f", v); }

    private static String gb(long kb) { return String.format(Locale.US, "%.1f", kb / 1048576f); }

    void close() {
        try { shIn.write("exit\n".getBytes()); shIn.flush(); } catch (Exception ignored) {}
        sh.destroy();
    }
}
