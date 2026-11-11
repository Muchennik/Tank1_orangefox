package com.muchennik.rearscreen;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.util.Log;

/**
 * Audio spectrum for the rear screen. Two sources:
 *  - microphone (AudioRecord + own FFT): reacts to whatever sounds around the phone;
 *  - phone output (Visualizer on the global mix, session 0): what the phone plays —
 *    speaker, wired or Bluetooth headphones.
 */
final class VisSource {
    static final int SRC_MIC = 0, SRC_OUT = 1;
    static final int STYLE_BARS = 0, STYLE_MIRROR = 1;
    private static final int N = 1024, BANDS = 20, RATE = 44100;

    private final int src, style;
    private final float gain;
    private AudioRecord rec;
    private Visualizer vis;
    private final short[] pcm = new short[N];
    private final float[] re = new float[N], im = new float[N];
    private byte[] visFft;
    private final float[] level = new float[BANDS], peak = new float[BANDS];
    private float agc = 1e-3f;
    private final Bitmap bmp = Bitmap.createBitmap(Frames.W, Frames.H, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(bmp);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int rate = RATE;

    VisSource(int src, int style, int sensitivity) {
        this.src = src;
        this.style = style;
        this.gain = sensitivity / 10f;
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
            rate = vis.getSamplingRate() / 1000; // mHz -> Hz
        }
    }

    /** Magnitude spectrum, bin i = i * rate / (2 * bins) Hz. */
    private float[] spectrum() {
        if (rec != null) {
            int got = 0;
            while (got < N) {
                int r = rec.read(pcm, got, N - got);
                if (r <= 0) break;
                got += r;
            }
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
        int bins = visFft.length / 2;
        float[] m = new float[bins];
        m[0] = Math.abs(visFft[0]);
        for (int i = 1; i < bins; i++) m[i] = (float) Math.hypot(visFft[2 * i], visFft[2 * i + 1]);
        return m;
    }

    void render(byte[] frame) {
        float[] m = spectrum();
        int bins = m.length;
        float binHz = rate / 2f / bins;
        float max = 0;
        float[] band = new float[BANDS];
        for (int b = 0; b < BANDS; b++) { // log bands 40 Hz .. 16 kHz
            float f0 = (float) (40 * Math.pow(400, b / (float) BANDS)), f1 = (float) (40 * Math.pow(400, (b + 1) / (float) BANDS));
            int i0 = Math.max(1, (int) (f0 / binHz)), i1 = Math.max(i0 + 1, Math.min(bins, (int) (f1 / binHz)));
            float s = 0;
            for (int i = i0; i < i1 && i < bins; i++) s = Math.max(s, m[i]);
            band[b] = s;
            max = Math.max(max, s);
        }
        // automatic gain: follows the loudest band, falls slowly; sensitivity scales it
        agc = Math.max(max, agc * 0.995f);
        agc = Math.max(agc, src == SRC_MIC ? 0.5f : 8f);
        for (int b = 0; b < BANDS; b++) {
            float v = Math.min(1f, band[b] / agc * gain);
            v = (float) Math.sqrt(v);
            level[b] = v > level[b] ? v : level[b] * 0.8f + v * 0.2f;
            peak[b] = Math.max(level[b], peak[b] - 0.012f);
        }
        draw();
        Frames.fromBitmap(bmp, frame);
    }

    private void draw() {
        canvas.drawColor(Color.BLACK);
        float slot = Frames.W / (float) BANDS, bw = slot - 3;
        for (int b = 0; b < BANDS; b++) {
            float x = b * slot + 1.5f;
            int segs = 24;
            int lit = Math.round(level[b] * segs);
            float segH = (style == STYLE_MIRROR ? Frames.H / 2f : Frames.H - 6) / segs;
            for (int s = 0; s < lit; s++) {
                paint.setColor(color(s / (float) segs));
                if (style == STYLE_MIRROR) {
                    float mid = Frames.H / 2f;
                    canvas.drawRect(x, mid - (s + 1) * segH + 1, x + bw, mid - s * segH, paint);
                    canvas.drawRect(x, mid + s * segH, x + bw, mid + (s + 1) * segH - 1, paint);
                } else {
                    float bottom = Frames.H - s * segH;
                    canvas.drawRect(x, bottom - segH + 1.5f, x + bw, bottom, paint);
                }
            }
            if (style == STYLE_BARS) { // falling peak cap
                paint.setColor(Color.WHITE);
                float y = Frames.H - peak[b] * (Frames.H - 6) - 3;
                canvas.drawRect(x, y, x + bw, y + 2.5f, paint);
            }
        }
    }

    /** green -> yellow -> red along the bar height */
    private static int color(float t) {
        int r = (int) (t < 0.5f ? 510 * t : 255), g = (int) (t < 0.5f ? 220 : 220 - 440 * (t - 0.5f));
        return Color.rgb(Math.min(255, r), Math.max(0, g), 40);
    }

    int delayMs() { return src == SRC_MIC ? 0 : 30; } // the mic read already paces the loop

    void close() {
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception e) { Log.w(Root.TAG, "mic: " + e); }
        try { if (vis != null) { vis.setEnabled(false); vis.release(); } } catch (Exception e) { Log.w(Root.TAG, "vis: " + e); }
    }
}
