package com.muchennik.rearscreen;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;

import java.nio.ByteBuffer;

/**
 * Video without sound, looped. MediaCodec decodes to YUV buffers; every output pixel of the
 * 240x296 frame samples the decoded picture directly (fit mode, rotation), so no GL is needed.
 */
final class VideoSource extends Source {
    private final MediaExtractor ex = new MediaExtractor();
    private final MediaCodec codec;
    private final int fit;
    private int rotation;
    private int[] sx, sy; // per output pixel: source x/y in display orientation, -1 = black
    private int mapW = -1, mapH = -1;
    private long baseMs = -1;
    private boolean inputDone;
    private long lastPtsUs;

    VideoSource(Context c, Uri uri, int fit) throws Exception {
        this.fit = fit;
        ex.setDataSource(c, uri, null);
        MediaFormat f = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat t = ex.getTrackFormat(i);
            String mime = t.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                ex.selectTrack(i);
                f = t;
                break;
            }
        }
        if (f == null) throw new IllegalArgumentException("no video track");
        if (f.containsKey(MediaFormat.KEY_ROTATION)) rotation = f.getInteger(MediaFormat.KEY_ROTATION);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
        codec.configure(f, null, null, 0);
        codec.start();
    }

    /** Builds the sampling map for a decoded picture of w x h (before rotation). */
    private void buildMap(int w, int h) {
        mapW = w;
        mapH = h;
        boolean swap = rotation == 90 || rotation == 270;
        int dw = swap ? h : w, dh = swap ? w : h; // displayed size
        RectF d = Frames.dest(dw, dh, fit);
        int n = Frames.W * Frames.H;
        sx = new int[n];
        sy = new int[n];
        for (int y = 0; y < Frames.H; y++)
            for (int x = 0; x < Frames.W; x++) {
                int i = y * Frames.W + x;
                float u = (x + 0.5f - d.left) / d.width() * dw, v = (y + 0.5f - d.top) / d.height() * dh;
                if (u < 0 || v < 0 || u >= dw || v >= dh) { sx[i] = -1; continue; }
                int iu = (int) u, iv = (int) v, px, py;
                switch (rotation) {
                    case 90: px = iv; py = h - 1 - iu; break;
                    case 180: px = w - 1 - iu; py = h - 1 - iv; break;
                    case 270: px = w - 1 - iv; py = iu; break;
                    default: px = iu; py = iv;
                }
                sx[i] = px;
                sy[i] = py;
            }
    }

    @Override void render(byte[] out) {
        if (baseMs < 0) baseMs = SystemClock.uptimeMillis();
        long targetUs = (SystemClock.uptimeMillis() - baseMs) * 1000;
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        // decode until we reach the frame that is due now (drops frames if we are late)
        for (int guard = 0; guard < 120; guard++) {
            if (!inputDone) {
                int in = codec.dequeueInputBuffer(0);
                if (in >= 0) {
                    ByteBuffer buf = codec.getInputBuffer(in);
                    int n = ex.readSampleData(buf, 0);
                    if (n < 0) {
                        codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }
            int o = codec.dequeueOutputBuffer(bi, 5000);
            if (o < 0) continue;
            if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                codec.releaseOutputBuffer(o, false);
                loop();
                return;
            }
            boolean late = bi.presentationTimeUs < targetUs - 40_000;
            if (late) { // skip quickly
                codec.releaseOutputBuffer(o, false);
                continue;
            }
            Image img = codec.getOutputImage(o);
            if (img != null) {
                convert(img, out);
                img.close();
            }
            lastPtsUs = bi.presentationTimeUs;
            codec.releaseOutputBuffer(o, false);
            return;
        }
    }

    private void loop() {
        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        codec.flush();
        inputDone = false;
        baseMs = SystemClock.uptimeMillis();
        lastPtsUs = 0;
    }

    private void convert(Image img, byte[] out) {
        Rect crop = img.getCropRect();
        int w = crop.width(), h = crop.height();
        if (w != mapW || h != mapH) buildMap(w, h);
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
        int cx = crop.left, cy = crop.top;
        for (int i = 0; i < sx.length; i++) {
            int x = sx[i];
            if (x < 0) { out[2 * i] = 0; out[2 * i + 1] = 0; continue; }
            int y = sy[i] + cy;
            x += cx;
            int Y = (yb.get(y * yRow + x * yPix) & 0xFF) - 16;
            int U = (ub.get((y >> 1) * uRow + (x >> 1) * uPix) & 0xFF) - 128;
            int V = (vb.get((y >> 1) * vRow + (x >> 1) * vPix) & 0xFF) - 128;
            int c = 298 * (Y < 0 ? 0 : Y);
            int r = (c + 409 * V + 128) >> 8, g = (c - 100 * U - 208 * V + 128) >> 8, b = (c + 516 * U + 128) >> 8;
            Frames.put(out, i, r < 0 ? 0 : r > 255 ? 255 : r, g < 0 ? 0 : g > 255 ? 255 : g, b < 0 ? 0 : b > 255 ? 255 : b);
        }
    }

    @Override long delayMs() {
        // next frame roughly when its pts is due; cap at 30 fps
        long now = (SystemClock.uptimeMillis() - baseMs);
        long due = lastPtsUs / 1000 + 33 - now;
        return Math.max(5, Math.min(due, 33));
    }

    @Override void close() {
        try { codec.stop(); } catch (Exception ignored) {}
        codec.release();
        ex.release();
    }
}
