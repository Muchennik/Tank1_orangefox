package com.muchennik.rearscreen;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/**
 * Keyboard for the tiny rear screen: 4x4 big keys, phone-style multi-tap (RU / EN), digits.
 * Rear Screen switches to it while an app runs on the rear screen and back afterwards.
 */
public class RearIme extends InputMethodService {
    private static final String[] RU = {".,?!-1", "абвг2", "деёжз3", "ийкл4", "мноп5", "рсту6", "фхцч7", "шщъы8", "ьэюя9"};
    private static final String[] EN = {".,?!-1", "abc2", "def3", "ghi4", "jkl5", "mno6", "pqrs7", "tuv8", "wxyz9"};
    private static final int MULTITAP_MS = 900;
    private static final int BKSP = -1, SPACE = -2, ENTER = -3, SHIFT = -4, NUM = -5, LANG = -6, HIDE = -7, ZERO = -8, SYM = -9;
    private static final String SYMBOLS = "@#*+=/()\"':;%&_";

    private boolean ru = true, num, shift, caps;
    private int lastKey = -100, tapIndex;
    private long lastTap;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable commit = this::finishComposing;
    private KeyView view;

    @Override public boolean onEvaluateFullscreenMode() { return false; } // never the full-screen text box

    @Override public View onCreateInputView() {
        view = new KeyView(this);
        return view;
    }

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        int cls = info.inputType & EditorInfo.TYPE_MASK_CLASS;
        num = cls == EditorInfo.TYPE_CLASS_NUMBER || cls == EditorInfo.TYPE_CLASS_PHONE;
        lastKey = -100;
        if (view != null) view.invalidate();
    }

    @Override public void onFinishInput() {
        finishComposing();
        super.onFinishInput();
    }

    private void finishComposing() {
        h.removeCallbacks(commit);
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && lastKey >= 0) ic.finishComposingText();
        lastKey = -100;
    }

    /** labels of the 16 keys, row by row */
    String label(int k) {
        int r = k / 4, c = k % 4;
        if (c == 3) return new String[] {"⌫", "␣", "⏎", "▼"}[r];
        if (r == 3) return c == 0 ? (caps ? "⇪" : shift ? "⇧" : "⇧") : c == 1 ? (num ? "ABC" : "123") : (num ? "0" : (ru ? "RU" : "EN"));
        int d = r * 3 + c;
        if (num) return String.valueOf(d + 1);
        String s = (ru ? RU : EN)[d];
        String letters = s.substring(0, s.length() - 1);
        if (d == 0) return ".,?!";
        return shift || caps ? letters.toUpperCase() : letters;
    }

    int code(int k) {
        int r = k / 4, c = k % 4;
        if (c == 3) return new int[] {BKSP, SPACE, ENTER, HIDE}[r];
        if (r == 3) return c == 0 ? (num ? SYM : SHIFT) : c == 1 ? NUM : (num ? ZERO : LANG);
        return r * 3 + c;
    }

    void press(int k) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        int code = code(k);
        long now = SystemClock.uptimeMillis();
        if (code >= 0 && !num) { // multi-tap letter key
            String chars = (ru ? RU : EN)[code];
            if (code == lastKey && now - lastTap < MULTITAP_MS) {
                tapIndex = (tapIndex + 1) % chars.length();
            } else {
                finishComposing();
                tapIndex = 0;
            }
            String ch = String.valueOf(chars.charAt(tapIndex));
            if (shift || caps) ch = ch.toUpperCase();
            ic.setComposingText(ch, 1);
            lastKey = code;
            lastTap = now;
            h.removeCallbacks(commit);
            h.postDelayed(() -> { // letter accepted: one-shot shift ends
                finishComposing();
                if (shift && !caps) { shift = false; view.invalidate(); }
            }, MULTITAP_MS);
            return;
        }
        finishComposing();
        if (code >= 0) { ic.commitText(String.valueOf(code + 1), 1); return; }
        switch (code) {
            case BKSP: ic.deleteSurroundingText(1, 0); break;
            case SPACE: ic.commitText(" ", 1); break;
            case ZERO: ic.commitText("0", 1); break;
            case SYM: symbols(ic); break;
            case ENTER: {
                EditorInfo ei = getCurrentInputEditorInfo();
                int action = ei != null ? ei.imeOptions & EditorInfo.IME_MASK_ACTION : 0;
                if (ei != null && (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
                        && action > EditorInfo.IME_ACTION_NONE) ic.performEditorAction(action);
                else {
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                }
                break;
            }
            case SHIFT:
                if (caps) { caps = false; shift = false; }
                else if (shift && now - lastTap < 400) caps = true; // double tap = caps lock
                else shift = !shift;
                lastTap = now;
                break;
            case NUM: num = !num; break;
            case LANG: ru = !ru; break;
            case HIDE: requestHideSelf(0); break;
            default: break;
        }
        view.invalidate();
    }

    private int symIndex;
    private long symTap;
    /** "*#" key in digit mode: multi-tap through symbols */
    private void symbols(InputConnection ic) {
        long now = SystemClock.uptimeMillis();
        if (now - symTap < MULTITAP_MS) { symIndex = (symIndex + 1) % SYMBOLS.length(); ic.deleteSurroundingText(1, 0); }
        else symIndex = 0;
        symTap = now;
        ic.commitText(String.valueOf(SYMBOLS.charAt(symIndex)), 1);
    }

    void longPress(int k) {
        if (code(k) == LANG) { // globe: other keyboards
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showInputMethodPicker();
        } else if (code(k) == BKSP) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.deleteSurroundingText(20, 0);
        }
    }

    /** The keyboard: drawn by hand so it scales to whatever display it opens on. */
    final class KeyView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int down = -1;
        private final Runnable lp = () -> { if (down >= 0) { longPress(down); down = -2; invalidate(); } };

        KeyView(Context c) { super(c); }

        @Override protected void onMeasure(int w, int hs) {
            int width = MeasureSpec.getSize(w);
            // rear screen (240 px): 144 px tall; big screens: 0.55 x width, max 300 dp
            float d = getResources().getDisplayMetrics().density;
            int height = width <= 400 ? 144 : (int) Math.min(width * 0.55f, 300 * d);
            setMeasuredDimension(width, height);
        }

        @Override protected void onDraw(Canvas c) {
            c.drawColor(0xFF101010);
            float kw = getWidth() / 4f, kh = getHeight() / 4f, gap = Math.max(1.5f, kw * 0.03f);
            for (int k = 0; k < 16; k++) {
                float l = (k % 4) * kw + gap, t = (k / 4) * kh + gap;
                boolean func = k % 4 == 3 || k / 4 == 3;
                p.setColor(k == down ? 0xFF5A5A5A : func ? 0xFF2C2C2C : 0xFF3A3A3A);
                if (code(k) == SHIFT && (shift || caps)) p.setColor(0xFF2E6BD8);
                c.drawRoundRect(new RectF(l, t, l + kw - 2 * gap, t + kh - 2 * gap), gap * 2, gap * 2, p);
                String s = label(k);
                p.setColor(Color.WHITE);
                p.setTextAlign(Paint.Align.CENTER);
                float size = kh * (s.length() <= 2 ? 0.5f : s.length() <= 4 ? 0.36f : 0.3f);
                while (p.measureText(s) > kw - 4 * gap && size > 6) p.setTextSize(size -= 1);
                p.setTextSize(size);
                c.drawText(s, l + (kw - 2 * gap) / 2, t + (kh - 2 * gap) / 2 + size * 0.35f, p);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int k = Math.min(3, Math.max(0, (int) (e.getX() / (getWidth() / 4f))))
                    + 4 * Math.min(3, Math.max(0, (int) (e.getY() / (getHeight() / 4f))));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down = k;
                    postDelayed(lp, 600);
                    invalidate();
                    break;
                case MotionEvent.ACTION_UP:
                    removeCallbacks(lp);
                    if (down >= 0) press(down);
                    down = -1;
                    invalidate();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(lp);
                    down = -1;
                    invalidate();
                    break;
                default: break;
            }
            return true;
        }
    }
}
