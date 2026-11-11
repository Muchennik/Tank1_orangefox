package com.muchennik.rearscreen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.List;

/**
 * Owns the rear display while media is shown: talks to the root helper (frames in, touch out),
 * plays the playlist and applies the "when does it go out" rules.
 */
public class RearService extends Service {
    static final String ACTION_START = "start", ACTION_STOP = "stop", ACTION_BRIGHTNESS = "bl";
    private static final String TAG = Root.TAG;
    private static final int NOTIF = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private String helper;
    private Process proc;
    private OutputStream frames;
    private Thread renderThread;
    private volatile boolean running;
    private volatile boolean awake; // picture shown (false = black, backlight off)
    private volatile long awakeSince;
    private volatile int index;
    private volatile long slideSince;
    private long lastUp, lastUpX, lastUpY;
    private PowerManager.WakeLock wl;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (prefs.offMode() != Prefs.OFF_SCREEN) return;
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) sleep();
            else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) wake();
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        prefs = new Prefs(this);
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(a)) {
            stopAll();
        } else if (ACTION_BRIGHTNESS.equals(a)) {
            if (running && awake) backlight(prefs.brightness());
        } else if (!running) {
            begin();
        }
        return START_NOT_STICKY;
    }

    private void begin() {
        startForeground(NOTIF, notification());
        new Thread(() -> {
            try {
                if (!Root.available()) throw new IllegalStateException("нет root (su)");
                helper = Root.helper(this);
                Root.blackviewUi(false);
                // DKSystemUI reacts to the switch a moment later and turns the panel off
                // (closeViceScreen): wait for it, then the helper turns the panel on again
                Thread.sleep(2000);
                proc = Runtime.getRuntime().exec(new String[] {"su", "-c", helper + " serve"});
                frames = proc.getOutputStream();
                running = true;
                prefs.put("running", true);
                startReader();
                registerReceiver(screen, screenFilter());
                wl = ((PowerManager) getSystemService(POWER_SERVICE))
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rearscreen:play");
                index = 0;
                wake();
                renderThread = new Thread(this::renderLoop, "rear-render");
                renderThread.start();
                TileService.requestListeningState(this,
                        new android.content.ComponentName(this, RearTile.class));
            } catch (Exception e) {
                Log.e(TAG, "start", e);
                toast("Задний экран: " + e.getMessage());
                main.post(this::stopAll);
            }
        }, "rear-start").start();
    }

    private static IntentFilter screenFilter() {
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        return f;
    }

    private void startReader() {
        Process p = proc;
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (l.startsWith("U ")) {
                        String[] s = l.split(" ");
                        onTap(Integer.parseInt(s[1]), Integer.parseInt(s[2]));
                    } else if (l.startsWith("E ") || l.startsWith("I ")) {
                        Log.i(TAG, "helper: " + l);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "reader: " + e);
            }
            if (running) main.post(this::stopAll); // helper died
        }, "rear-touch").start();
    }

    /** Touch release on the rear screen: double tap detection. */
    private void onTap(int x, int y) {
        long now = SystemClock.uptimeMillis();
        boolean dbl = now - lastUp < 400 && Math.abs(x - lastUpX) < 60 && Math.abs(y - lastUpY) < 60;
        lastUp = dbl ? 0 : now;
        lastUpX = x;
        lastUpY = y;
        if (!dbl) return;
        int mode = prefs.offMode();
        if (!awake) {
            if (prefs.wakeOnDoubleTap()) wake();
        } else if (mode == Prefs.OFF_DOUBLE_TAP || mode == Prefs.OFF_NEVER) {
            main.post(this::stopAll);
        } else {
            sleep();
        }
    }

    private void wake() {
        awake = true;
        awakeSince = SystemClock.uptimeMillis();
        slideSince = awakeSince;
        String h = helper;
        int bl = prefs.brightness();
        // panel on (display on + sleep out) before the backlight, every time we wake up
        if (h != null) new Thread(() -> Root.run(h + " on && " + h + " bl " + bl)).start();
        if (wl != null && !wl.isHeld()) wl.acquire();
    }

    private void sleep() {
        awake = false;
        backlight(0);
        if (wl != null && wl.isHeld()) wl.release();
        if (!prefs.wakeOnDoubleTap() && prefs.offMode() != Prefs.OFF_SCREEN) main.post(this::stopAll);
    }

    private void backlight(int v) {
        String h = helper;
        if (h != null) new Thread(() -> Root.run(h + " bl " + v)).start();
    }

    private void renderLoop() {
        byte[] frame = new byte[Frames.SIZE];
        byte[] black = new byte[Frames.SIZE];
        Source src = null;
        String srcKey = null; // uri + fit of the open source: reloaded when either changes
        boolean blackSent = false;
        try {
            while (running) {
                List<String> media = prefs.media();
                long now = SystemClock.uptimeMillis();
                if (media.isEmpty()) { // list cleared while running: black, wait for new files
                    if (src != null) { src.close(); src = null; srcKey = null; }
                    if (!blackSent) {
                        frames.write(black);
                        frames.flush();
                        blackSent = true;
                    }
                    Thread.sleep(200);
                    continue;
                }
                if (awake && prefs.offMode() == Prefs.OFF_TIMER && now - awakeSince > prefs.timerSec() * 1000L) {
                    sleep();
                }
                if (!awake) {
                    if (!blackSent) {
                        frames.write(black);
                        frames.flush();
                        blackSent = true;
                    }
                    if (src != null) { src.close(); src = null; srcKey = null; }
                    Thread.sleep(100);
                    continue;
                }
                blackSent = false;
                if (media.size() > 1 && now - slideSince > prefs.slideSec() * 1000L) {
                    index = (index + 1) % media.size();
                    slideSince = now;
                }
                int want = index % media.size();
                String key = media.get(want) + "|" + prefs.fit();
                if (src == null || !key.equals(srcKey)) {
                    if (src != null) src.close();
                    src = null;
                    srcKey = null;
                    try {
                        src = Source.open(this, Uri.parse(media.get(want)), prefs.fit());
                    } catch (Exception e) {
                        Log.e(TAG, "open " + media.get(want), e);
                        toast("Не открыть файл: " + e.getMessage());
                        index = want + 1;
                        Thread.sleep(500);
                        continue;
                    }
                    srcKey = key;
                }
                src.render(frame);
                frames.write(frame);
                frames.flush();
                Thread.sleep(src.delayMs());
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "render", e);
        } finally {
            if (src != null) src.close();
        }
    }

    private void stopAll() {
        boolean was = running;
        running = false;
        prefs.put("running", false);
        try { unregisterReceiver(screen); } catch (Exception ignored) {}
        if (wl != null && wl.isHeld()) wl.release();
        Process p = proc;
        proc = null;
        new Thread(() -> {
            try {
                if (frames != null) frames.close(); // helper exits on EOF and releases the touch grab
            } catch (Exception ignored) {}
            if (p != null) {
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                p.destroy();
            }
            if (was || helper != null) Root.blackviewUi(true); // give the screen back to Blackview
            TileService.requestListeningState(this, new android.content.ComponentName(this, RearTile.class));
        }, "rear-stop").start();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (running) stopAll();
        super.onDestroy();
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("rear", "Задний экран", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RearService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "rear")
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("Задний экран")
                .setContentText("Показываю медиа")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Стоп", stop).build())
                .build();
    }

    private void toast(String s) {
        main.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }
}
