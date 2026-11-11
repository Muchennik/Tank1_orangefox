package com.muchennik.rearscreen;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.util.Log;

/**
 * Audio visualizer for the rear screen. Sources:
 *  - microphone (AudioRecord + own FFT): whatever sounds around the phone;
 *  - phone output (Visualizer on the global mix, session 0): speaker, wired or Bluetooth.
 * Style and sensitivity can change on every frame; only a source change reopens it.
 */
final class VisSource {
    static final int SRC_MIC = 0, SRC_OUT = 1;
    static final String[] STYLES = {"Столбики с пиками", "Зеркальные (от центра)", "Осциллограф (волна)",
            "Круг", "Водопад (спектрограмма)", "LED-матрица", "Горы (заливка)"};
    static final int STYLE_BARS = 0, STYLE_MIRROR = 1, STYLE_WAVE = 2, STYLE_CIRCLE = 3, STYLE_WATERFALL = 4,
            STYLE_LED = 5, STYLE_AREA = 6;
    private static final int N = 1024, HI = 60, LO = 20, RATE = 44100;

    final int src;
    private AudioRecord rec;
    private Visualizer vis;
    private final short[] pcm = new short[N];
    private final float[] re = new float[N], im = new float[N];
    private final float[] wave = new float[256]; // -1..1
    private byte[] visFft, visWave;
    private final float[] hi = new float[HI], lo = new float[LO], peak = new float[LO];
    private float agc = 1e-3f;
    private final Bitmap bmp = Bitmap.createBitmap(Frames.W, Frames.H, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(bmp);
    private final Bitmap fall = Bitmap.createBitmap(Frames.W, Frames.H, Bitmap.Config.ARGB_8888);
    private final Bitmap fall2 = Bitmap.createBitmap(Frames.W, Frames.H, Bitmap.Config.ARGB_8888);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int rate = RATE;
    private float hueShift;

    VisSource(int src) {
        this.src = src;
        if (src == SRC_MIC) {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, N * 4));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("микрофон недоступен (разрешение?)");
            rec.startRecording();
        } else {
            vis = new Visualizer(0); // global output mix
            int[] r = Visualizer.getCaptureSizeRange();
            vis.setCaptureSize(Math.min(N, r[1]));
            vis.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            vis.setEnabled(true);
            visFft = new byte[vis.getCaptureSize()];
            visWave = new byte[vis.getCaptureSize()];
            rate = vis.getSamplingRate() / 1000; // mHz -> Hz
        }
    }

    /** Magnitude spectrum (+ fills wave[]). */
    private float[] spectrum() {
        if (rec != null) {
            int got = 0;
            while (got < N) {
                int r = rec.read(pcm, got, N - got);
                if (r <= 0) break;
                got += r;
            }
            for (int i = 0; i < wave.length; i++) wave[i] = pcm[i * N / wave.length / 2] / 32768f;
            for (int i = 0; i < N; i++) {
                float w = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * i / (N - 1)); // Hann
                re[i] = pcm[i] / 32768f * w;
                im[i] = 0;
            }
            Fft.fft(re, im);
            float[] m = new float[N / 2];
            for (int i = 0; i < N / 2; i++) m[i] = (float) Math.hypot(re[i], im[i]);
            return m;
        }
        vis.getFft(visFft);
        vis.getWaveForm(visWave);
        for (int i = 0; i < wave.length; i++) wave[i] = ((visWave[i * visWave.length / wave.length / 2] & 0xFF) - 128) / 128f;
        int bins = visFft.length / 2;
        float[] m = new float[bins];
        m[0] = Math.abs(visFft[0]);
        for (int i = 1; i < bins; i++) m[i] = (float) Math.hypot(visFft[2 * i], visFft[2 * i + 1]);
        return m;
    }

    void render(byte[] frame, int style, int sensitivity) {
        float gain = sensitivity / 10f;
        float[] m = spectrum();
        int bins = m.length;
        float binHz = rate / 2f / bins;
        float max = 0;
        float[] band = new float[HI];
        for (int b = 0; b < HI; b++) { // log bands 40 Hz .. 16 kHz
            float f0 = (float) (40 * Math.pow(400, b / (float) HI)), f1 = (float) (40 * Math.pow(400, (b + 1) / (float) HI));
            int i0 = Math.max(1, (int) (f0 / binHz)), i1 = Math.max(i0 + 1, Math.min(bins, (int) Math.ceil(f1 / binHz)));
            float s = 0;
            for (int i = i0; i < i1 && i < bins; i++) s = Math.max(s, m[i]);
            band[b] = s;
            max = Math.max(max, s);
        }
        // automatic gain: follows the loudest band, falls slowly; sensitivity scales it
        agc = Math.max(max, agc * 0.995f);
        agc = Math.max(agc, src == SRC_MIC ? 0.5f : 8f);
        for (int b = 0; b < HI; b++) {
            float v = (float) Math.sqrt(Math.min(1f, band[b] / agc * gain));
            hi[b] = v > hi[b] ? v : hi[b] * 0.8f + v * 0.2f;
        }
        for (int b = 0; b < LO; b++) {
            lo[b] = Math.max(hi[3 * b], Math.max(hi[3 * b + 1], hi[3 * b + 2]));
            peak[b] = Math.max(lo[b], peak[b] - 0.012f);
        }
        for (int i = 0; i < wave.length; i++) wave[i] = Math.max(-1, Math.min(1, wave[i] * gain * 1.5f));
        hueShift = (hueShift + 0.6f) % 360;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawColor(Color.BLACK);
        switch (style) {
            case STYLE_MIRROR: bars(true); break;
            case STYLE_WAVE: wave(); break;
            case STYLE_CIRCLE: circle(); break;
            case STYLE_WATERFALL: waterfall(); break;
            case STYLE_LED: led(); break;
            case STYLE_AREA: area(); break;
            default: bars(false); break;
        }
        Frames.fromBitmap(bmp, frame);
    }

    private void bars(boolean mirror) {
        float slot = Frames.W / (float) LO, bw = slot - 3;
        int segs = 24;
        float segH = (mirror ? Frames.H / 2f : Frames.H - 6) / segs;
        for (int b = 0; b < LO; b++) {
            float x = b * slot + 1.5f;
            int lit = Math.round(lo[b] * segs);
            for (int s = 0; s < lit; s++) {
                paint.setColor(heat(s / (float) segs));
                if (mirror) {
                    float mid = Frames.H / 2f;
                    canvas.drawRect(x, mid - (s + 1) * segH + 1, x + bw, mid - s * segH, paint);
                    canvas.drawRect(x, mid + s * segH, x + bw, mid + (s + 1) * segH - 1, paint);
                } else {
                    float bottom = Frames.H - s * segH;
                    canvas.drawRect(x, bottom - segH + 1.5f, x + bw, bottom, paint);
                }
            }
            if (!mirror) { // falling peak cap
                paint.setColor(Color.WHITE);
                float y = Frames.H - peak[b] * (Frames.H - 6) - 3;
                canvas.drawRect(x, y, x + bw, y + 2.5f, paint);
            }
        }
    }

    private void wave() {
        Path p = new Path();
        float mid = Frames.H / 2f;
        for (int i = 0; i < wave.length; i++) {
            float x = i * (Frames.W - 1f) / (wave.length - 1), y = mid - wave[i] * (Frames.H / 2f - 8);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.HSVToColor(60, new float[] {(180 + hueShift) % 360, 1, 1}));
        paint.setStrokeWidth(9);
        canvas.drawPath(p, paint); // glow
        paint.setColor(Color.HSVToColor(new float[] {(180 + hueShift) % 360, 0.6f, 1}));
        paint.setStrokeWidth(2.5f);
        canvas.drawPath(p, paint);
        paint.setColor(0x30FFFFFF);
        paint.setStrokeWidth(1);
        canvas.drawLine(0, mid, Frames.W, mid, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void circle() {
        float cx = Frames.W / 2f, cy = Frames.H / 2f;
        float bass = (hi[0] + hi[1] + hi[2] + hi[3]) / 4;
        float r0 = 34 + bass * 14;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(4);
        int n = 2 * HI; // spectrum mirrored around the circle
        for (int k = 0; k < n; k++) {
            float v = hi[k < HI ? k : n - 1 - k];
            double a = 2 * Math.PI * k / n - Math.PI / 2;
            float len = 4 + v * 72;
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            paint.setColor(Color.HSVToColor(new float[] {(k * 360f / n + hueShift) % 360, 0.9f, 1}));
            canvas.drawLine(cx + c * r0, cy + s * r0, cx + c * (r0 + len), cy + s * (r0 + len), paint);
        }
        paint.setStrokeWidth(2);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, r0 - 5, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void waterfall() {
        // scroll the history up 2 px, draw the newest spectrum line at the bottom
        Canvas c2 = new Canvas(fall2);
        c2.drawColor(Color.BLACK);
        c2.drawBitmap(fall, 0, -2, null);
        float w = Frames.W / (float) HI;
        for (int b = 0; b < HI; b++) {
            paint.setColor(fire(hi[b]));
            c2.drawRect(b * w, Frames.H - 2, (b + 1) * w + 0.5f, Frames.H, paint);
        }
        new Canvas(fall).drawBitmap(fall2, 0, 0, null);
        canvas.drawBitmap(fall, 0, 0, null);
    }

    private void led() {
        int cols = 15, rows = 18;
        float cw = Frames.W / (float) cols, rh = Frames.H / (float) rows, r = Math.min(cw, rh) * 0.38f;
        for (int c = 0; c < cols; c++) {
            float v = hi[c * HI / cols];
            int lit = Math.round(v * rows);
            for (int y = 0; y < rows; y++) {
                boolean on = y < lit;
                int col = Color.HSVToColor(new float[] {(120 - 120f * y / rows + 360) % 360, 1, on ? 1 : 0.15f});
                paint.setColor(col);
                canvas.drawCircle(c * cw + cw / 2, Frames.H - (y * rh + rh / 2), r, paint);
            }
        }
    }

    private void area() {
        Path p = new Path();
        p.moveTo(0, Frames.H);
        float step = Frames.W / (float) (HI - 1);
        float px = 0, py = Frames.H - hi[0] * (Frames.H - 20);
        p.lineTo(px, py);
        for (int b = 1; b < HI; b++) { // smooth curve through the band tops
            float x = b * step, y = Frames.H - hi[b] * (Frames.H - 20);
            p.quadTo(px, py, (px + x) / 2, (py + y) / 2);
            px = x; py = y;
        }
        p.lineTo(Frames.W, py);
        p.lineTo(Frames.W, Frames.H);
        p.close();
        int top = Color.HSVToColor(new float[] {(280 + hueShift) % 360, 0.8f, 1});
        int bottom = Color.HSVToColor(new float[] {(200 + hueShift) % 360, 0.9f, 0.35f});
        paint.setShader(new LinearGradient(0, 20, 0, Frames.H, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawPath(p, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(Color.WHITE);
        canvas.drawPath(p, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** green -> yellow -> red along the bar height */
    private static int heat(float t) {
        int r = (int) (t < 0.5f ? 510 * t : 255), g = (int) (t < 0.5f ? 220 : 220 - 440 * (t - 0.5f));
        return Color.rgb(Math.min(255, r), Math.max(0, g), 40);
    }

    /** black -> blue -> magenta -> orange -> yellow-white */
    private static int fire(float v) {
        v = Math.max(0, Math.min(1, v));
        int r = (int) (255 * Math.min(1, v * 2)), g = (int) (255 * Math.max(0, v * 2 - 1)),
                b = (int) (255 * (v < 0.4f ? v / 0.4f : Math.max(0, 1 - (v - 0.4f) * 2.5f)));
        return Color.rgb(r, g, Math.max(b, (int) (255 * Math.max(0, v - 0.85f) * 6)));
    }

    int delayMs() { return src == SRC_MIC ? 0 : 30; } // the mic read already paces the loop

    void close() {
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception e) { Log.w(Root.TAG, "mic: " + e); }
        try { if (vis != null) { vis.setEnabled(false); vis.release(); } } catch (Exception e) { Log.w(Root.TAG, "vis: " + e); }
    }
}
