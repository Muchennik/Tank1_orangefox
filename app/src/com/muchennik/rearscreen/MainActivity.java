package com.muchennik.rearscreen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Settings screen, built in code (no resources, no AndroidX). Dark, card-based. */
public class MainActivity extends Activity {
    private static final int PICK = 1;
    private static final int[] TIMER_STEPS = {5, 10, 15, 30, 45, 60, 120, 180, 300, 600, 900, 1200, 1800, 2700, 3600};

    // palette
    static final int BG = 0xFF0D0E12, CARD = 0xFF17191F, CARD2 = 0xFF21242C, LINE = 0xFF2A2E37,
            TEXT = 0xFFF1F2F5, SUB = 0xFF8A90A0, ACCENT = 0xFFFF7A3D, ACCENT_DIM = 0x33FF7A3D, OK = 0xFF4ADE80;

    private Prefs prefs;
    private LinearLayout page, appsBox, appSec, visSec, mediaSec, filesBox, tickSec, qrSec, wifiBox, contactBox, textBox;
    private TextView statusText, statusDot, startBtn, previewGlyph;
    private final List<View> modeTiles = new ArrayList<>();
    private final List<Runnable> refreshers = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0)
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 2);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(22), dp(18), dp(32));
        sv.addView(page);
        setContentView(sv);

        // ---- title
        TextView title = text("Задний экран", 30, TEXT, true);
        title.setLetterSpacing(-0.01f);
        page.addView(title);
        page.addView(text("Oscal Tank 1 · 240 × 296", 13, SUB, false));
        space(page, 18);

        page.addView(hero());
        space(page, 22);

        // ---- mode tiles
        page.addView(label("РЕЖИМ"));
        LinearLayout tiles = column();
        LinearLayout tileRow = null;
        String[][] modes = {{"▣", "Медиа", "фото · GIF · видео"}, {"⠿", "Приложения", "иконки сзади"},
                {"▁▃▆", "Эквалайзер", "звук"}, {"◔", "Монитор", "ЦП · ОЗУ · батарея"},
                {"≋", "Строка", "бегущий текст"}, {"▦", "QR-код", "Wi-Fi · ссылка"}};
        for (int i = 0; i < modes.length; i++) {
            if (i % 2 == 0) {
                tileRow = new LinearLayout(this);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) rp.topMargin = dp(10);
                tiles.addView(tileRow, rp);
            }
            final int m = i;
            View t = modeTile(modes[i][0], modes[i][1], modes[i][2], v -> {
                prefs.put("mode", m);
                if (m == Prefs.MODE_VIS) askMic();
                refresh();
                live();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(96), 1);
            if (i % 2 == 1) lp.leftMargin = dp(10);
            tileRow.addView(t, lp);
            modeTiles.add(t);
        }
        page.addView(tiles);
        space(page, 22);

        // ---- per-mode sections
        appSec = column(); visSec = column(); mediaSec = column(); tickSec = column(); qrSec = column();
        page.addView(appSec); page.addView(visSec); page.addView(mediaSec); page.addView(tickSec); page.addView(qrSec);
        buildApps();
        buildVis();
        buildMedia();
        buildTicker();
        buildQr();

        // ---- common
        page.addView(label("ЭКРАН"));
        LinearLayout common = card();
        common.addView(picker("Когда гаснет", new String[] {"Через время", "Вместе с основным экраном",
                "По двойному тапу сзади", "Никогда (только «Стоп»)"}, prefs::offMode, i -> prefs.put("off", i)));
        divider(common);
        common.addView(slider("Время до погасания", TIMER_STEPS.length - 1, () -> indexOf(prefs.timerSec()),
                i -> prefs.put("timer", TIMER_STEPS[i]), () -> {
                    int t = prefs.timerSec();
                    return (t < 60 ? t + " с" : t / 60 + " мин") + (prefs.offMode() == Prefs.OFF_TIMER ? "" : " · не исп.");
                }));
        divider(common);
        common.addView(picker("Как включать снова", new String[] {"Никак (экран отдаётся Blackview)",
                "Одиночный тап сзади", "Двойной тап сзади"}, prefs::wakeMode, i -> prefs.put("wake", i)));
        divider(common);
        common.addView(slider("Яркость", 15, () -> prefs.brightness() - 1, i -> {
            prefs.put("bl", i + 1);
            if (prefs.running()) startService(new Intent(this, RearService.class).setAction(RearService.ACTION_BRIGHTNESS));
        }, () -> prefs.brightness() + " / 16"));
        page.addView(common);
        space(page, 14);
        page.addView(hint("Экран возвращается Blackview только кнопкой «Остановить» — здесь, в уведомлении или "
                + "плиткой «Задний экран» в шторке."));
        refresh();
    }

    // ===================================================================== sections

    private View hero() {
        LinearLayout c = card();
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));

        // a tiny "rear panel" with the current mode glyph
        FrameLayout panel = new FrameLayout(this);
        panel.setBackground(round(0xFF000000, dp(12), LINE, dp(2)));
        previewGlyph = text("", 22, ACCENT, true);
        previewGlyph.setGravity(Gravity.CENTER);
        panel.addView(previewGlyph, new FrameLayout.LayoutParams(-1, -1));
        c.addView(panel, new LinearLayout.LayoutParams(dp(60), dp(74)));

        LinearLayout mid = column();
        mid.setPadding(dp(14), 0, dp(8), 0);
        LinearLayout st = new LinearLayout(this);
        st.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = text("●", 12, SUB, false);
        st.addView(statusDot);
        statusText = text("", 15, TEXT, true);
        statusText.setPadding(dp(6), 0, 0, 0);
        st.addView(statusText);
        mid.addView(st);
        TextView sub = text("Тап и свайп — прямо по заднему экрану", 12, SUB, false);
        sub.setPadding(0, dp(3), 0, 0);
        mid.addView(sub);
        c.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));

        startBtn = text("", 14, Color.WHITE, true);
        startBtn.setGravity(Gravity.CENTER);
        startBtn.setPadding(dp(18), dp(12), dp(18), dp(12));
        startBtn.setOnClickListener(v -> {
            if (prefs.running()) {
                startService(new Intent(this, RearService.class).setAction(RearService.ACTION_STOP));
            } else {
                if (prefs.mode() == Prefs.MODE_VIS && checkSelfPermission("android.permission.RECORD_AUDIO") != 0) {
                    askMic();
                    return;
                }
                startForegroundService(new Intent(this, RearService.class).setAction(RearService.ACTION_START));
            }
            startBtn.postDelayed(this::refresh, 700);
            startBtn.postDelayed(this::refresh, 2600);
        });
        c.addView(startBtn);
        return c;
    }

    private void buildApps() {
        appSec.addView(label("ПРИЛОЖЕНИЯ НА ЗАДНЕМ ЭКРАНЕ"));
        LinearLayout c = card();
        appsBox = column();
        c.addView(appsBox);
        divider(c);
        LinearLayout r = new LinearLayout(this);
        r.setPadding(dp(12), dp(12), dp(12), dp(12));
        r.addView(pill("+  Добавить", true, v -> addApps()), new LinearLayout.LayoutParams(0, -2, 1));
        View menu = pill("Меню сзади", false, v ->
                startService(new Intent(this, RearService.class).setAction(RearService.ACTION_MENU)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.leftMargin = dp(10);
        r.addView(menu, lp);
        c.addView(r);
        appSec.addView(c);
        space(appSec, 8);
        appSec.addView(hint("Сзади — сетка иконок 3×3, свайп влево/вправо листает. «Меню сзади» возвращает к иконкам."));
        space(appSec, 14);

        LinearLayout k = card();
        k.addView(picker("Клавиатура", new String[] {"Своя, на заднем экране", "Обычная, на основном экране"},
                () -> prefs.rearKeyboard() ? 0 : 1, i -> prefs.put("kbd_rear", i == 0)));
        divider(k);
        k.addView(slider("Масштаб приложения", 140, () -> prefs.dpi() - 100, i -> prefs.put("dpi", i + 100),
                () -> prefs.dpi() + " dpi"));
        appSec.addView(k);
        space(appSec, 8);
        appSec.addView(hint("Своя клавиатура — как на кнопочном телефоне: «абвг» 1–4 раза. ⇧ заглавная (×2 капс), "
                + "123 цифры, RU/EN язык, ▼ скрыть. Масштаб — со следующего запуска; меньше dpi — больше влезает."));
        space(appSec, 22);
    }

    private void buildVis() {
        visSec.addView(label("ЭКВАЛАЙЗЕР"));
        LinearLayout c = card();
        c.addView(picker("Источник", new String[] {"Звук телефона", "Микрофон"},
                () -> prefs.visSource() == VisSource.SRC_OUT ? 0 : 1,
                i -> { prefs.put("vis_src", i == 0 ? VisSource.SRC_OUT : VisSource.SRC_MIC); askMic(); }));
        divider(c);
        c.addView(picker("Вид", VisSource.STYLES, prefs::visStyle, i -> prefs.put("vis_style", i)));
        divider(c);
        c.addView(slider("Чувствительность", 29, () -> prefs.visSensitivity() - 1, i -> prefs.put("vis_sens", i + 1),
                () -> String.valueOf(prefs.visSensitivity())));
        visSec.addView(c);
        space(visSec, 8);
        visSec.addView(hint("Всё меняется на ходу. «Звук телефона» — динамик, проводные и Bluetooth-наушники."));
        space(visSec, 22);
    }

    private void buildMedia() {
        mediaSec.addView(label("МЕДИА"));
        LinearLayout c = card();
        filesBox = column();
        c.addView(filesBox);
        divider(c);
        LinearLayout r = new LinearLayout(this);
        r.setPadding(dp(12), dp(12), dp(12), dp(12));
        r.addView(pill("+  Файлы", true, v -> pick()), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.leftMargin = dp(10);
        r.addView(pill("Очистить", false, v -> { prefs.setMedia(new ArrayList<>()); refresh(); }), lp);
        c.addView(r);
        mediaSec.addView(c);
        space(mediaSec, 14);

        LinearLayout s = card();
        s.addView(picker("Подгонка", new String[] {"Заполнить (обрезать края)", "Вписать (поля)", "Растянуть"},
                prefs::fit, i -> prefs.put("fit", i)));
        divider(s);
        s.addView(slider("Слайд-шоу", 297, () -> prefs.slideSec() - 3, i -> prefs.put("slide", i + 3),
                () -> "каждые " + prefs.slideSec() + " с"));
        mediaSec.addView(s);
        space(mediaSec, 8);
        mediaSec.addView(hint("JPG, PNG, WebP, GIF, MP4, WebM. Несколько файлов — слайд-шоу."));
        space(mediaSec, 22);
    }

    private void buildTicker() {
        tickSec.addView(label("БЕГУЩАЯ СТРОКА"));
        LinearLayout c = card();
        c.addView(field("Текст", "tk_text", prefs.tickerText(), false, android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        divider(c);
        c.addView(picker("Вид", TextSource.TICKER_STYLES, prefs::tickerStyle, i -> prefs.put("tk_style", i)));
        divider(c);
        c.addView(picker("Цвет", TextSource.COLORS, prefs::tickerColor, i -> prefs.put("tk_color", i)));
        divider(c);
        c.addView(picker("Размер", TextSource.SIZES, prefs::tickerSize, i -> prefs.put("tk_size", i)));
        divider(c);
        c.addView(slider("Скорость", 9, prefs::tickerSpeed, i -> prefs.put("tk_speed", i),
                () -> String.valueOf(prefs.tickerSpeed() + 1)));
        tickSec.addView(c);
        space(tickSec, 8);
        tickSec.addView(hint("Всё меняется на ходу, эмодзи тоже можно. «Статично» — текст по центру, "
                + "переносится и сам уменьшается, чтобы влезть."));
        space(tickSec, 22);
    }

    private void buildQr() {
        qrSec.addView(label("QR-КОД"));
        LinearLayout c = card();
        c.addView(picker("Что в коде", TextSource.QR_TYPES, prefs::qrType, i -> prefs.put("qr_type", i)));
        textBox = column();
        divider(textBox);
        textBox.addView(field("Текст или ссылка", "qr_text", prefs.qrText(), false,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI));
        divider(textBox);
        textBox.addView(field("Подпись под кодом (необязательно)", "qr_caption", prefs.qrCaption(), false,
                android.text.InputType.TYPE_CLASS_TEXT));
        c.addView(textBox);
        wifiBox = column();
        divider(wifiBox);
        wifiBox.addView(field("Имя сети (SSID)", "qr_ssid", prefs.qrWifiSsid(), false, android.text.InputType.TYPE_CLASS_TEXT));
        divider(wifiBox);
        wifiBox.addView(field("Пароль (пусто — открытая сеть)", "qr_pass", prefs.qrWifiPass(), true,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        c.addView(wifiBox);
        contactBox = column();
        divider(contactBox);
        contactBox.addView(field("Имя", "qr_name", prefs.qrName(), false,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS));
        divider(contactBox);
        contactBox.addView(field("Телефон", "qr_phone", prefs.qrPhone(), false, android.text.InputType.TYPE_CLASS_PHONE));
        c.addView(contactBox);
        qrSec.addView(c);
        space(qrSec, 8);
        qrSec.addView(hint("Код обновляется сразу. Wi-Fi-код подключает к сети камерой — без ввода пароля. "
                + "Если камера плохо ловит — прибавь яркость."));
        space(qrSec, 22);
    }

    /** text input row, saved to prefs as you type */
    private View field(String title, String key, String value, boolean secret, int inputType) {
        LinearLayout c = column();
        c.setPadding(dp(16), dp(12), dp(16), dp(12));
        c.addView(text(title, 12, SUB, false));
        android.widget.EditText e = new android.widget.EditText(this);
        e.setText(value);
        e.setTextColor(TEXT);
        e.setHintTextColor(0xFF555A66);
        e.setTextSize(16);
        e.setInputType(inputType);
        e.setBackground(null);
        e.setPadding(0, dp(4), 0, 0);
        e.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c2) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c2) {}
            @Override public void afterTextChanged(android.text.Editable s) { prefs.put(key, s.toString()); }
        });
        c.addView(e);
        return c;
    }

    // ===================================================================== state

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (statusText == null) return;
        boolean on = prefs.running();
        int mode = prefs.mode();
        statusText.setText(on ? "Показ идёт" : "У Blackview");
        statusDot.setTextColor(on ? OK : SUB);
        startBtn.setText(on ? "Остановить" : "Запустить");
        startBtn.setTextColor(on ? TEXT : Color.WHITE);
        startBtn.setBackground(ripple(on ? round(CARD2, dp(24), LINE, dp(1)) : round(ACCENT, dp(24), 0, 0)));
        previewGlyph.setText(new String[] {"▣", "⠿", "▁▃▆", "◔", "≋", "▦"}[Math.min(5, mode)]);
        previewGlyph.setTextColor(on ? ACCENT : 0xFF3A3F4A);
        for (int i = 0; i < modeTiles.size(); i++) {
            boolean sel = i == mode;
            modeTiles.get(i).setBackground(ripple(round(sel ? ACCENT_DIM : CARD, dp(18), sel ? ACCENT : LINE, dp(sel ? 2 : 1))));
        }
        appSec.setVisibility(mode == Prefs.MODE_APP ? View.VISIBLE : View.GONE);
        visSec.setVisibility(mode == Prefs.MODE_VIS ? View.VISIBLE : View.GONE);
        mediaSec.setVisibility(mode == Prefs.MODE_MEDIA ? View.VISIBLE : View.GONE);
        tickSec.setVisibility(mode == Prefs.MODE_TICKER ? View.VISIBLE : View.GONE);
        qrSec.setVisibility(mode == Prefs.MODE_QR ? View.VISIBLE : View.GONE);
        int qt = prefs.qrType();
        textBox.setVisibility(qt == 0 ? View.VISIBLE : View.GONE);
        wifiBox.setVisibility(qt == 1 ? View.VISIBLE : View.GONE);
        contactBox.setVisibility(qt == 2 ? View.VISIBLE : View.GONE);
        for (Runnable r : refreshers) r.run();
        fillApps();
        fillFiles();
    }

    private void fillFiles() {
        filesBox.removeAllViews();
        List<String> m = prefs.media();
        if (m.isEmpty()) {
            TextView t = text("Файлы не выбраны", 14, SUB, false);
            t.setPadding(dp(16), dp(16), dp(16), dp(16));
            filesBox.addView(t);
            return;
        }
        for (int k = 0; k < m.size(); k++) {
            final int i = k;
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(16), dp(10), dp(8), dp(10));
            String n = name(Uri.parse(m.get(k)));
            if (n == null) n = "?";
            String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1).toUpperCase() : "?";
            TextView badge = text(ext.length() > 4 ? ext.substring(0, 4) : ext, 10, ACCENT, true);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(round(ACCENT_DIM, dp(8), 0, 0));
            r.addView(badge, new LinearLayout.LayoutParams(dp(40), dp(28)));
            TextView t = text(n, 14, TEXT, false);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            t.setPadding(dp(12), 0, dp(8), 0);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(iconBtn("✕", v -> { List<String> l = prefs.media(); l.remove(i); prefs.setMedia(l); refresh(); }));
            filesBox.addView(r);
        }
    }

    /** the grid list with move up / down / remove */
    private void fillApps() {
        appsBox.removeAllViews();
        List<String> apps = prefs.apps();
        if (apps.isEmpty()) {
            TextView t = text("Список пуст — добавь приложения", 14, SUB, false);
            t.setPadding(dp(16), dp(16), dp(16), dp(16));
            appsBox.addView(t);
        }
        for (int k = 0; k < apps.size(); k++) {
            final int i = k;
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(16), dp(8), dp(6), dp(8));
            ImageView iv = new ImageView(this);
            try { iv.setImageDrawable(getPackageManager().getApplicationIcon(apps.get(k))); } catch (Exception ignored) {}
            r.addView(iv, new LinearLayout.LayoutParams(dp(38), dp(38)));
            TextView t = text(labelOf(apps.get(k)), 15, TEXT, false);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(dp(14), 0, dp(4), 0);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            View up = iconBtn("↑", v -> move(i, -1));
            View dn = iconBtn("↓", v -> move(i, 1));
            up.setAlpha(k == 0 ? 0.25f : 1f);
            dn.setAlpha(k == apps.size() - 1 ? 0.25f : 1f);
            r.addView(up);
            r.addView(dn);
            r.addView(iconBtn("✕", v -> { List<String> l = prefs.apps(); l.remove(i); prefs.setApps(l); refresh(); live(); }));
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
        live();
    }

    /** tell a running rear screen about the change (mode / app list) */
    private void live() {
        if (prefs.running()) startService(new Intent(this, RearService.class).setAction(RearService.ACTION_SWITCH));
    }

    private void askMic() {
        if (checkSelfPermission("android.permission.RECORD_AUDIO") != 0)
            requestPermissions(new String[] {"android.permission.RECORD_AUDIO"}, 3);
    }

    private String labelOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private void addApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all = pm.queryIntentActivities(main, 0);
        List<String> have = prefs.apps();
        List<String> pkgs = new ArrayList<>(), names = new ArrayList<>();
        all.sort((a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        for (ResolveInfo r : all) {
            String p = r.activityInfo.packageName;
            if (have.contains(p) || pkgs.contains(p) || p.equals(getPackageName())) continue;
            pkgs.add(p);
            names.add(r.loadLabel(pm).toString());
        }
        boolean[] checked = new boolean[pkgs.size()];
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Добавить на задний экран")
                .setMultiChoiceItems(names.toArray(new String[0]), checked, (d, k, on) -> checked[k] = on)
                .setPositiveButton("Добавить", (d, w) -> {
                    List<String> l = prefs.apps();
                    for (int k = 0; k < checked.length; k++) if (checked[k]) l.add(pkgs.get(k));
                    prefs.setApps(l);
                    refresh();
                    live();
                })
                .setNegativeButton("Отмена", null)
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

    // ===================================================================== widgets

    interface IntCb { void on(int i); }
    interface IntGet { int get(); }
    interface StrGet { String get(); }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private void space(ViewGroup g, int h) {
        g.addView(new View(this), new LinearLayout.LayoutParams(1, dp(h)));
    }

    private TextView label(String s) {
        TextView t = text(s, 11.5f, SUB, true);
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(4), 0, 0, dp(8));
        return t;
    }

    private TextView hint(String s) {
        TextView t = text(s, 12, 0xFF6C7282, false);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(dp(4), 0, dp(4), 0);
        return t;
    }

    static GradientDrawable round(int fill, float radius, int stroke, int strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokeW > 0) g.setStroke(strokeW, stroke);
        return g;
    }

    private Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), content, round(Color.WHITE, dp(18), 0, 0));
    }

    private LinearLayout card() {
        LinearLayout c = column();
        c.setBackground(round(CARD, dp(20), LINE, dp(1)));
        c.setClipToOutline(true);
        return c;
    }

    private void divider(LinearLayout card) {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.leftMargin = dp(16);
        card.addView(v, lp);
    }

    private View modeTile(String glyph, String name, String sub, View.OnClickListener l) {
        LinearLayout t = column();
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), dp(10), dp(6), dp(10));
        TextView g = text(glyph, 22, ACCENT, true);
        g.setGravity(Gravity.CENTER);
        t.addView(g);
        TextView n = text(name, 14, TEXT, true);
        n.setGravity(Gravity.CENTER);
        n.setPadding(0, dp(6), 0, 0);
        t.addView(n);
        TextView s = text(sub, 11, SUB, false);
        s.setGravity(Gravity.CENTER);
        t.addView(s);
        t.setOnClickListener(l);
        return t;
    }

    private TextView pill(String s, boolean primary, View.OnClickListener l) {
        TextView b = text(s, 14, primary ? Color.WHITE : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        b.setBackground(ripple(primary ? round(ACCENT, dp(22), 0, 0) : round(CARD2, dp(22), LINE, dp(1))));
        b.setOnClickListener(l);
        return b;
    }

    private TextView iconBtn(String s, View.OnClickListener l) {
        TextView b = text(s, 17, SUB, false);
        b.setGravity(Gravity.CENTER);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null));
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        return b;
    }

    /** row: title + current value, tap opens a single-choice list */
    private View picker(String title, String[] items, IntGet get, IntCb set) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(14), dp(14), dp(14));
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18FFFFFF), null, new ColorDrawable(Color.WHITE)));
        LinearLayout col = column();
        col.addView(text(title, 15, TEXT, false));
        TextView v = text("", 13, ACCENT, false);
        v.setPadding(0, dp(2), 0, 0);
        col.addView(v);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(text("›", 22, SUB, false));
        Runnable upd = () -> v.setText(items[Math.max(0, Math.min(items.length - 1, get.get()))]);
        refreshers.add(upd);
        upd.run();
        r.setOnClickListener(x -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setSingleChoiceItems(items, get.get(), (d, k) -> {
                    d.dismiss();
                    set.on(k);
                    refresh();
                })
                .show());
        return r;
    }

    /** row: title + value on the right, accent slider below */
    private View slider(String title, int max, IntGet get, IntCb set, StrGet value) {
        LinearLayout c = column();
        c.setPadding(dp(16), dp(14), dp(16), dp(8));
        LinearLayout top = new LinearLayout(this);
        top.addView(text(title, 15, TEXT, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = text("", 13, ACCENT, true);
        top.addView(v);
        c.addView(top);
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(get.get());
        s.setProgressTintList(ColorStateList.valueOf(ACCENT));
        s.setThumbTintList(ColorStateList.valueOf(ACCENT));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF3A3F4A));
        s.setPadding(dp(4), dp(10), dp(4), dp(6));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                if (!user) return;
                set.on(p);
                v.setText(value.get());
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) { refresh(); }
        });
        c.addView(s);
        Runnable upd = () -> v.setText(value.get());
        refreshers.add(upd);
        upd.run();
        return c;
    }
}
