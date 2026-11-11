package com.muchennik.rearscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** One media item rendered into rear-display frames. */
abstract class Source {
    /** Fills out (Frames.SIZE bytes) with the current frame. */
    abstract void render(byte[] out);

    /** Milliseconds until the next frame is due. */
    abstract long delayMs();

    void close() {}

    static Source open(Context c, Uri uri, int fit) throws Exception {
        String type = c.getContentResolver().getType(uri);
        if (type == null) type = "";
        if (type.startsWith("video/")) return new VideoSource(c, uri, fit);
        if (type.equals("image/gif")) {
            byte[] data = read(c, uri);
            Movie m = Movie.decodeByteArray(data, 0, data.length);
            if (m != null && m.duration() > 0) return new GifSource(m, fit);
        }
        return new ImageSource(c, uri, fit);
    }

    static byte[] read(Context c, Uri uri) throws Exception {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        }
    }

    /** Still picture (also first frame of animated WebP/HEIF). */
    static final class ImageSource extends Source {
        private final byte[] frame = new byte[Frames.SIZE];

        ImageSource(Context c, Uri uri, int fit) throws Exception {
            ImageDecoder.Source src = ImageDecoder.createSource(c.getContentResolver(), uri);
            Bitmap img = ImageDecoder.decodeBitmap(src, (dec, info, s) -> {
                dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                // decode near the target size: phone photos are 50 MP
                int sw = info.getSize().getWidth(), sh = info.getSize().getHeight();
                int sample = 1;
                while (sw / (sample * 2) >= Frames.W * 2 && sh / (sample * 2) >= Frames.H * 2) sample *= 2;
                dec.setTargetSampleSize(sample);
            });
            Bitmap out = Bitmap.createBitmap(Frames.W, Frames.H, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            cv.drawColor(Color.BLACK);
            cv.drawBitmap(img, null, Frames.dest(img.getWidth(), img.getHeight(), fit),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            Frames.fromBitmap(out, frame);
            img.recycle();
            out.recycle();
        }

        @Override void render(byte[] out) { System.arraycopy(frame, 0, out, 0, frame.length); }

        @Override long delayMs() { return 1000; }
    }

    /** Animated GIF through android.graphics.Movie (works on a software canvas). */
    static final class GifSource extends Source {
        private final Movie movie;
        private final Bitmap bmp = Bitmap.createBitmap(Frames.W, Frames.H, Bitmap.Config.ARGB_8888);
        private final Canvas cv = new Canvas(bmp);
        private final RectF d;
        private final long start = android.os.SystemClock.uptimeMillis();

        GifSource(Movie m, int fit) {
            movie = m;
            d = Frames.dest(m.width(), m.height(), fit);
        }

        @Override void render(byte[] out) {
            int t = (int) ((android.os.SystemClock.uptimeMillis() - start) % movie.duration());
            movie.setTime(t);
            cv.drawColor(Color.BLACK);
            cv.save();
            cv.translate(d.left, d.top);
            cv.scale(d.width() / movie.width(), d.height() / movie.height());
            movie.draw(cv, 0, 0);
            cv.restore();
            Frames.fromBitmap(bmp, out);
        }

        @Override long delayMs() { return 33; }

        @Override void close() { bmp.recycle(); }
    }
}
