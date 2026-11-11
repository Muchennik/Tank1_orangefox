package com.muchennik.rearscreen;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Settings screen (built in code: no resources, no AndroidX). */
public class MainActivity extends Activity {
    private static final int PICK = 1;
    private static final int[] TIMER_STEPS = {5, 10, 15, 30, 45, 60, 120, 180, 300, 600, 900, 1200, 1800, 2700, 3600};
    private Prefs prefs;
    private TextView files, timerLabel, slideLabel, blLabel, status;
    private LinearLayout root;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0)
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 2);

        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);
        setContentView(sv);

        status = text("", 15, true);
        root.addView(status);

        header("Медиа");
        files = text("", 14, false);
        root.addView(files);
        LinearLayout row = hrow();
        row.addView(button("Выбрать файлы", v -> pick()));
        row.addView(button("Очистить", v -> { prefs.setMedia(new ArrayList<>()); refresh(); }));
        root.addView(row);
        root.addView(text("Фото (JPG/PNG/WebP), GIF и видео (MP4/WebM, без звука, по кругу). "
                + "Несколько файлов — слайд-шоу.", 12, false));

        header("Подгонка под экран 240×296");
        RadioGroup fit = radios(new String[] {"Заполнить (обрезать края)", "Вписать (поля)", "Растянуть"},
                prefs.fit(), i -> prefs.put("fit", i));
        root.addView(fit);

        header("Когда гаснет");
        root.addView(radios(new String[] {"Через время", "Вместе с основным экраном",
                        "По двойному тапу сзади", "Никогда (только двойной тап / Стоп)"},
                prefs.offMode(), i -> { prefs.put("off", i); refresh(); }));
        timerLabel = text("", 14, false);
        root.addView(timerLabel);
        root.addView(seek(TIMER_STEPS.length - 1, indexOf(prefs.timerSec()), i -> {
            prefs.put("timer", TIMER_STEPS[i]);
            refresh();
        }));
        header("Как включать снова (когда погасло)");
        root.addView(radios(new String[] {"Никак (погасло — экран отдаётся Blackview)",
                        "Одиночным тапом по заднему экрану", "Двойным тапом по заднему экрану"},
                prefs.wakeMode(), i -> prefs.put("wake", i)));
        root.addView(text("Например: «Через время» + «Одиночным тапом» — тапнул сзади, показало и "
                + "погасло через заданное время.", 12, false));

        header("Слайд-шоу");
        slideLabel = text("", 14, false);
        root.addView(slideLabel);
        root.addView(seek(297, prefs.slideSec() - 3, i -> { prefs.put("slide", i + 3); refresh(); }));

        header("Яркость");
        blLabel = text("", 14, false);
        root.addView(blLabel);
        root.addView(seek(15, prefs.brightness() - 1, i -> {
            prefs.put("bl", i + 1);
            refresh();
            if (prefs.running()) startService(new Intent(this, RearService.class).setAction(RearService.ACTION_BRIGHTNESS));
        }));

        header("Управление");
        LinearLayout ctl = hrow();
        ctl.addView(button("▶ Показать", v -> {
            startForegroundService(new Intent(this, RearService.class).setAction(RearService.ACTION_START));
            status.postDelayed(this::refresh, 800);
        }));
        ctl.addView(button("■ Стоп (экран Blackview)", v -> {
            startService(new Intent(this, RearService.class).setAction(RearService.ACTION_STOP));
            status.postDelayed(this::refresh, 800);
        }));
        root.addView(ctl);
        root.addView(text("Двойной тап сзади: погасить (или выключить в режимах «по двойному тапу» и "
                + "«никогда»). Плитка «Задний экран» в шторке включает и выключает показ.\n\n"
                + "Режим «Приложение на заднем экране» (Chrome и т.п. с тачем и клавиатурой) — в следующей версии.",
                12, false));
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (status == null) return;
        status.setText(prefs.running() ? "● Показ идёт" : "○ Задний экран у Blackview");
        List<String> m = prefs.media();
        StringBuilder sb = new StringBuilder(m.isEmpty() ? "Файлы не выбраны" : "Файлов: " + m.size());
        for (String u : m) sb.append("\n• ").append(name(Uri.parse(u)));
        files.setText(sb);
        int t = prefs.timerSec();
        timerLabel.setText("Время: " + (t < 60 ? t + " с" : t / 60 + " мин")
                + (prefs.offMode() == Prefs.OFF_TIMER ? "" : " (только для режима «Через время»)"));
        slideLabel.setText("Смена файла каждые " + prefs.slideSec() + " с");
        blLabel.setText("Яркость: " + prefs.brightness() + " / 16");
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "video/*"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req != PICK || res != RESULT_OK || data == null) return;
        List<String> m = new ArrayList<>(prefs.media());
        List<Uri> got = new ArrayList<>();
        if (data.getClipData() != null)
            for (int k = 0; k < data.getClipData().getItemCount(); k++) got.add(data.getClipData().getItemAt(k).getUri());
        else if (data.getData() != null) got.add(data.getData());
        for (Uri u : got) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            if (!m.contains(u.toString())) m.add(u.toString());
        }
        prefs.setMedia(m);
        refresh();
    }

    private String name(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return u.getLastPathSegment();
    }

    private int indexOf(int sec) {
        for (int i = 0; i < TIMER_STEPS.length; i++) if (TIMER_STEPS[i] >= sec) return i;
        return TIMER_STEPS.length - 1;
    }

    // --- tiny UI helpers ---
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private void header(String s) {
        TextView t = text(s, 17, true);
        t.setPadding(0, dp(18), 0, dp(4));
        root.addView(t);
    }

    private LinearLayout hrow() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return b;
    }

    interface IntCb { void on(int i); }

    private RadioGroup radios(String[] items, int checked, IntCb cb) {
        RadioGroup g = new RadioGroup(this);
        for (int i = 0; i < items.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(items[i]);
            r.setId(1000 + i);
            g.addView(r);
        }
        g.check(1000 + checked);
        g.setOnCheckedChangeListener((gr, id) -> cb.on(id - 1000));
        return g;
    }

    private SeekBar seek(int max, int value, IntCb cb) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(value);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) { if (user) cb.on(p); }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        return s;
    }
}
