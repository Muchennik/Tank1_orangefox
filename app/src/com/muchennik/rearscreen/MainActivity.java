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
    private TextView files, timerLabel, slideLabel, blLabel, status, dpiLabel, sensLabel;
    private LinearLayout root, page, appsBox, appSec, visSec, mediaSec;

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
        page = root;
        setContentView(sv);

        status = text("", 15, true);
        root.addView(status);

        header("Что показывать сзади");
        root.addView(radios(new String[] {"Медиа (фото, GIF, видео)", "Приложения (меню иконок на заднем экране)",
                        "Эквалайзер (звук)"},
                prefs.mode(), i -> { prefs.put("mode", i); if (i == Prefs.MODE_VIS) askMic(); refresh(); }));

        // only the section of the chosen mode is shown
        appSec = section(); visSec = section(); mediaSec = section();
        root = appSec;
        header("Приложения на заднем экране");
        root.addView(text("Сзади — сетка иконок (3×3, свайп влево/вправо — следующая страница). Тап по иконке "
                + "открывает приложение. Вернуться к иконкам — кнопкой «Меню» (здесь или в уведомлении).", 12, false));
        appsBox = new LinearLayout(this);
        appsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(appsBox);
        LinearLayout ar = hrow();
        ar.addView(button("+ Добавить", v -> addApps()));
        ar.addView(button("Меню на заднем экране", v ->
                startService(new Intent(this, RearService.class).setAction(RearService.ACTION_MENU))));
        root.addView(ar);
        header("Клавиатура для приложений");
        root.addView(radios(new String[] {"Своя, на заднем экране (крупные клавиши, мультитап RU/EN)",
                        "Обычная, на основном экране"},
                prefs.rearKeyboard() ? 0 : 1, i -> prefs.put("kbd_rear", i == 0)));
        root.addView(text("Своя клавиатура: как на кнопочном телефоне — жмёшь «абвг» 1–4 раза. ⇧ — заглавная "
                + "(2 раза — капс), 123 — цифры, RU/EN — язык (долгое нажатие — выбор другой клавиатуры), "
                + "▼ — скрыть. На время работы она становится текущей клавиатурой телефона, после «Стоп» "
                + "возвращается твоя.", 12, false));
        dpiLabel = text("", 14, false);
        root.addView(dpiLabel);
        root.addView(seek(140, prefs.dpi() - 100, i -> { prefs.put("dpi", i + 100); refresh(); }));
        root.addView(text("Меньше плотность — больше влезает (мельче). Применяется при следующем запуске. "
                + "Управление: тап/свайп по заднему экрану; «Назад», «Меню», «Стоп» — в уведомлении.", 12, false));

        root = visSec;
        header("Эквалайзер");
        root.addView(radios(new String[] {"Звук телефона (динамик, наушники, Bluetooth)", "Микрофон (звук вокруг)"},
                prefs.visSource() == VisSource.SRC_OUT ? 0 : 1,
                i -> { prefs.put("vis_src", i == 0 ? VisSource.SRC_OUT : VisSource.SRC_MIC); askMic(); }));
        root.addView(radios(VisSource.STYLES, prefs.visStyle(), i -> prefs.put("vis_style", i)));
        sensLabel = text("", 14, false);
        root.addView(sensLabel);
        root.addView(seek(29, prefs.visSensitivity() - 1, i -> { prefs.put("vis_sens", i + 1); refresh(); }));
        root.addView(text("Меняется сразу, на ходу. Для обоих источников Android просит доступ к "
                + "микрофону (так устроен захват звука).", 12, false));

        root = mediaSec;
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
        View fit = radios(new String[] {"Заполнить (обрезать края)", "Вписать (поля)", "Растянуть"},
                prefs.fit(), i -> prefs.put("fit", i));
        root.addView(fit);

        header("Слайд-шоу");
        slideLabel = text("", 14, false);
        root.addView(slideLabel);
        root.addView(seek(297, prefs.slideSec() - 3, i -> { prefs.put("slide", i + 3); refresh(); }));

        root = page; // common settings
        header("Когда гаснет");
        root.addView(radios(new String[] {"Через время", "Вместе с основным экраном",
                        "По двойному тапу сзади", "Никогда (только кнопкой «Стоп»)"},
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
            if (prefs.mode() == Prefs.MODE_VIS && checkSelfPermission("android.permission.RECORD_AUDIO") != 0) {
                askMic();
                return;
            }
            startForegroundService(new Intent(this, RearService.class).setAction(RearService.ACTION_START));
            status.postDelayed(this::refresh, 800);
        }));
        ctl.addView(button("■ Стоп (экран Blackview)", v -> {
            startService(new Intent(this, RearService.class).setAction(RearService.ACTION_STOP));
            status.postDelayed(this::refresh, 800);
        }));
        root.addView(ctl);
        root.addView(text("Двойной тап сзади гасит картинку (в режиме «Медиа»). Экран возвращается Blackview только "
                + "кнопкой «Стоп» (здесь, в уведомлении или плиткой «Задний экран» в шторке).\n\n"
                + "Кнопка «Показать» запускает выбранный режим: медиа или приложение.",
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
        int mode = prefs.mode();
        appSec.setVisibility(mode == Prefs.MODE_APP ? View.VISIBLE : View.GONE);
        visSec.setVisibility(mode == Prefs.MODE_VIS ? View.VISIBLE : View.GONE);
        mediaSec.setVisibility(mode == Prefs.MODE_MEDIA ? View.VISIBLE : View.GONE);
        sensLabel.setText("Чувствительность: " + prefs.visSensitivity());
        fillApps();
        dpiLabel.setText("Плотность: " + prefs.dpi() + " dpi");
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

    private void askMic() {
        if (checkSelfPermission("android.permission.RECORD_AUDIO") != 0)
            requestPermissions(new String[] {"android.permission.RECORD_AUDIO"}, 3);
    }

    private String labelOf(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** the grid list with move up / down / remove */
    private void fillApps() {
        appsBox.removeAllViews();
        List<String> apps = prefs.apps();
        if (apps.isEmpty()) appsBox.addView(text("Список пуст", 14, false));
        for (int k = 0; k < apps.size(); k++) {
            final int i = k;
            LinearLayout r = hrow();
            r.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.widget.ImageView iv = new android.widget.ImageView(this);
            try { iv.setImageDrawable(getPackageManager().getApplicationIcon(apps.get(k))); } catch (Exception ignored) {}
            iv.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
            r.addView(iv);
            TextView t = text("  " + (k + 1) + ". " + labelOf(apps.get(k)), 15, false);
            t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            r.addView(t);
            r.addView(small("▲", v -> move(i, -1)));
            r.addView(small("▼", v -> move(i, 1)));
            r.addView(small("✕", v -> { List<String> l = prefs.apps(); l.remove(i); prefs.setApps(l); refresh(); }));
            appsBox.addView(r);
        }
    }

    private void move(int i, int d) {
        List<String> l = prefs.apps();
        int j = i + d;
        if (j < 0 || j >= l.size()) return;
        String x = l.get(i); l.set(i, l.get(j)); l.set(j, x);
        prefs.setApps(l);
        refresh();
    }

    private Button small(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private void addApps() {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> all = pm.queryIntentActivities(main, 0);
        List<String> have = prefs.apps();
        List<String> pkgs = new ArrayList<>(), names = new ArrayList<>();
        all.sort((a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        for (android.content.pm.ResolveInfo r : all) {
            String p = r.activityInfo.packageName;
            if (have.contains(p) || pkgs.contains(p) || p.equals(getPackageName())) continue;
            pkgs.add(p);
            names.add(r.loadLabel(pm).toString());
        }
        boolean[] checked = new boolean[pkgs.size()];
        new android.app.AlertDialog.Builder(this)
                .setTitle("Добавить на задний экран")
                .setMultiChoiceItems(names.toArray(new String[0]), checked, (d, k, on) -> checked[k] = on)
                .setPositiveButton("Добавить", (d, w) -> {
                    List<String> l = prefs.apps();
                    for (int k = 0; k < checked.length; k++) if (checked[k]) l.add(pkgs.get(k));
                    prefs.setApps(l);
                    refresh();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pickApp() {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        apps.sort((a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        String[] names = new String[apps.size()];
        for (int k = 0; k < names.length; k++) names[k] = apps.get(k).loadLabel(pm).toString();
        new android.app.AlertDialog.Builder(this)
                .setTitle("Приложение для заднего экрана")
                .setItems(names, (d, k) -> {
                    prefs.putApp(apps.get(k).activityInfo.packageName, names[k]);
                    refresh();
                })
                .show();
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

    private String lastHeader = "";

    private void header(String s) {
        lastHeader = s;
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

    private LinearLayout section() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        page.addView(l);
        return l;
    }

    /** One line with the current choice; tap opens the list (instead of a long radio list). */
    private View radios(String[] items, int checked, IntCb cb) {
        final String title = lastHeader;
        final int[] cur = {Math.max(0, Math.min(items.length - 1, checked))};
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        b.setText(items[cur[0]] + "   ▾");
        b.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(items, cur[0], (d, k) -> {
                    cur[0] = k;
                    b.setText(items[k] + "   ▾");
                    d.dismiss();
                    cb.on(k);
                })
                .show());
        return b;
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
