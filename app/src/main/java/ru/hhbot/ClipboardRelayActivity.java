package ru.hhbot;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Крошечная прозрачная Activity, единственная задача которой — получить фокус
 * и прочитать системный буфер обмена. На Android 10+ читать буфер может только
 * приложение, находящееся в фокусе, поэтому служба доступности (работающая в фоне)
 * открывает это окно, забирает текст и сразу закрывается.
 */
public class ClipboardRelayActivity extends Activity {

    static final String EXTRA_PURPOSE = "purpose";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean handled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Даём окну окончательно стать активным, затем читаем буфер.
            ui.postDelayed(this::readClipboard, 120);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Страховка, если onWindowFocusChanged почему-то не вызвался.
        ui.postDelayed(this::readClipboard, 350);
    }

    private void readClipboard() {
        if (handled) {
            return;
        }
        handled = true;

        String purpose = getIntent() == null
                ? "" : String.valueOf(getIntent().getStringExtra(EXTRA_PURPOSE));

        String text = "";
        try {
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData clip = cm.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence cs = clip.getItemAt(0).coerceToText(this);
                    if (cs != null) {
                        text = cs.toString();
                    }
                }
            }
        } catch (Throwable t) {
            BotLog.add("Не удалось прочитать буфер обмена: " + t);
        }

        getSharedPreferences(HhAccessibilityService.PREFS, MODE_PRIVATE).edit()
                .putString(HhAccessibilityService.KEY_CLIPBOARD, text)
                .putString(HhAccessibilityService.KEY_CLIPBOARD_PURPOSE, purpose)
                .putLong(HhAccessibilityService.KEY_CLIPBOARD_AT,
                        System.currentTimeMillis())
                .apply();

        finish();
    }
}
