package ru.hhbot;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String DEFAULT_QUERY = "embedded-программист";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView serviceStatus;
    private TextView appStatus;
    private EditText packageInput;
    private EditText queryInput;
    private TextView logView;
    private ScrollView logScroll;

    private long lastLogVersion = -1;

    private final Runnable logRefresher = new Runnable() {
        @Override
        public void run() {
            refreshLog();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        loadPrefs();
        detectApp(false);
        ui.post(logRefresher);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatuses();
        refreshLog();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private View buildUi() {
        int pad = dp(20);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.WHITE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("HH Bot");
        title.setTextSize(30);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        title.setTextColor(Color.parseColor("#D6001C"));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Автоматизация hh.ru: находит приложение, открывает его, "
                + "вводит запрос в поиск и нажимает «Найти».");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.parseColor("#555555"));
        subtitle.setPadding(0, dp(4), 0, dp(16));
        root.addView(subtitle);

        serviceStatus = new TextView(this);
        serviceStatus.setTextSize(14);
        serviceStatus.setTypeface(Typeface.DEFAULT_BOLD);
        serviceStatus.setPadding(0, 0, 0, dp(4));
        root.addView(serviceStatus);

        appStatus = new TextView(this);
        appStatus.setTextSize(14);
        appStatus.setPadding(0, 0, 0, dp(14));
        root.addView(appStatus);

        addLabel(root, "Поисковый запрос");
        queryInput = new EditText(this);
        queryInput.setSingleLine(true);
        queryInput.setInputType(InputType.TYPE_CLASS_TEXT);
        queryInput.setText(DEFAULT_QUERY);
        queryInput.setTextSize(16);
        root.addView(queryInput, matchWrap());

        addLabel(root, "Пакет приложения hh.ru");
        packageInput = new EditText(this);
        packageInput.setSingleLine(true);
        packageInput.setInputType(InputType.TYPE_CLASS_TEXT);
        packageInput.setText("ru.hh.android");
        packageInput.setTextSize(14);
        root.addView(packageInput, matchWrap());

        Button enable = makeButton("1. Включить службу доступности", false);
        enable.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });
        root.addView(enable, buttonParams());

        Button check = makeButton("2. Проверить приложение hh.ru", false);
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                detectApp(true);
            }
        });
        root.addView(check, buttonParams());

        Button run = makeButton("3. Запустить hh.ru и выполнить поиск", true);
        run.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runScenario();
            }
        });
        root.addView(run, buttonParams());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button stop = makeButton("Остановить", false);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopScenario();
            }
        });
        row.addView(stop, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button clear = makeButton("Очистить журнал", false);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BotLog.clear();
                refreshLog();
            }
        });
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clearParams.setMargins(dp(8), 0, 0, 0);
        row.addView(clear, clearParams);

        root.addView(row, buttonParams());

        addLabel(root, "Журнал работы");
        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.parseColor("#F4F4F4"));
        logScroll.setPadding(dp(10), dp(8), dp(10), dp(8));

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(Color.parseColor("#212121"));
        logView.setTextIsSelectable(true);
        logScroll.addView(logView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240));
        root.addView(logScroll, logParams);

        TextView note = new TextView(this);
        note.setText("Как это работает: служба доступности читает элементы интерфейса "
                + "hh.ru и выполняет действия от вашего имени — как Selenium в браузере. "
                + "Никакие данные никуда не отправляются. Приложение не связано с hh.ru.\n\n"
                + "Важно: не блокируйте экран во время работы. Если hh.ru откроется "
                + "на незнакомом экране (например, запросит вход), верните его на главный "
                + "экран и нажмите «Запустить» ещё раз.");
        note.setTextSize(11);
        note.setTextColor(Color.parseColor("#777777"));
        note.setPadding(0, dp(14), 0, 0);
        root.addView(note);

        return scroll;
    }

    private TextView addLabel(LinearLayout parent, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(13);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(Color.parseColor("#333333"));
        label.setPadding(0, dp(12), 0, dp(2));
        parent.addView(label);
        return label;
    }

    private Button makeButton(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        if (primary) {
            button.setTextColor(Color.WHITE);
            button.setBackgroundTintList(ColorStateList.valueOf(
                    Color.parseColor("#D6001C")));
        } else {
            button.setTextColor(Color.parseColor("#D6001C"));
            button.setBackgroundTintList(ColorStateList.valueOf(
                    Color.parseColor("#F3E1E3")));
        }
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(10), 0, 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    // ------------------------------------------------------------------
    // Логика
    // ------------------------------------------------------------------

    private SharedPreferences prefs() {
        return getSharedPreferences(HhAccessibilityService.PREFS, Context.MODE_PRIVATE);
    }

    private void loadPrefs() {
        SharedPreferences sp = prefs();
        queryInput.setText(sp.getString(HhAccessibilityService.KEY_QUERY, DEFAULT_QUERY));
        packageInput.setText(sp.getString(HhAccessibilityService.KEY_PACKAGE, "ru.hh.android"));
    }

    private void savePrefs() {
        prefs().edit()
                .putString(HhAccessibilityService.KEY_QUERY, queryInput.getText().toString())
                .putString(HhAccessibilityService.KEY_PACKAGE,
                        packageInput.getText().toString().trim())
                .apply();
    }

    private void detectApp(boolean verbose) {
        String found = AppFinder.findHhPackage(this);
        if (found == null) {
            appStatus.setText("● hh.ru не найдено на телефоне");
            appStatus.setTextColor(Color.parseColor("#C62828"));
            if (verbose) {
                BotLog.add("hh.ru не найдено среди установленных приложений");
                toast("Приложение hh.ru не установлено");
            }
            return;
        }

        packageInput.setText(found);
        String version = AppFinder.versionName(this, found);
        appStatus.setText("● hh.ru найдено: " + found + " (версия " + version + ")");
        appStatus.setTextColor(Color.parseColor("#2E7D32"));
        if (verbose) {
            BotLog.add("hh.ru найдено: " + found + ", версия " + version);
        }
        savePrefs();
    }

    private void refreshStatuses() {
        boolean enabled = isServiceEnabled();
        if (enabled) {
            serviceStatus.setText("● Служба доступности включена");
            serviceStatus.setTextColor(Color.parseColor("#2E7D32"));
        } else {
            serviceStatus.setText("● Служба доступности выключена — нажмите кнопку 1");
            serviceStatus.setTextColor(Color.parseColor("#C62828"));
        }
    }

    private boolean isServiceEnabled() {
        try {
            AccessibilityManager manager =
                    (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager != null) {
                for (AccessibilityServiceInfo info :
                        manager.getEnabledAccessibilityServiceList(
                                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                    if (info.getResolveInfo() != null
                            && info.getResolveInfo().serviceInfo != null
                            && getPackageName().equals(
                                    info.getResolveInfo().serviceInfo.packageName)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String target = getPackageName() + "/" + HhAccessibilityService.class.getName();
            if (enabled != null) {
                for (String part : enabled.split(":")) {
                    if (part.equalsIgnoreCase(target)
                            || part.toLowerCase().startsWith(getPackageName().toLowerCase() + "/")) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            toast("Найдите «HH Bot» в списке и включите службу");
        } catch (Throwable t) {
            toast("Не удалось открыть настройки: " + t.getMessage());
        }
    }

    private void runScenario() {
        hideKeyboard();

        String query = queryInput.getText().toString().trim();
        if (query.isEmpty()) {
            toast("Введите поисковый запрос");
            return;
        }

        String pkg = packageInput.getText().toString().trim();
        if (pkg.isEmpty() || !AppFinder.isInstalled(this, pkg)) {
            String detected = AppFinder.findHhPackage(this);
            if (detected == null) {
                appStatus.setText("● hh.ru не найдено на телефоне");
                appStatus.setTextColor(Color.parseColor("#C62828"));
                BotLog.add("Запуск отменён: hh.ru не установлено");
                toast("Приложение hh.ru не установлено");
                return;
            }
            pkg = detected;
            packageInput.setText(pkg);
        }

        if (!isServiceEnabled()) {
            BotLog.add("Запуск отменён: служба доступности выключена");
            toast("Сначала включите службу доступности (кнопка 1)");
            openAccessibilitySettings();
            return;
        }

        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            BotLog.add("Не удалось получить intent запуска для " + pkg);
            toast("hh.ru установлено, но не запускается");
            return;
        }

        BotLog.clear();
        BotLog.add("Запрос: «" + query + "»");
        BotLog.add("Запускаю " + pkg + " ...");

        prefs().edit()
                .putString(HhAccessibilityService.KEY_QUERY, query)
                .putString(HhAccessibilityService.KEY_PACKAGE, pkg)
                .putBoolean(HhAccessibilityService.KEY_PENDING, true)
                .putLong(HhAccessibilityService.KEY_STARTED, System.currentTimeMillis())
                .apply();

        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            BotLog.add("Приложение hh.ru запущено, жду его окно...");
        } catch (Throwable t) {
            prefs().edit().putBoolean(HhAccessibilityService.KEY_PENDING, false).apply();
            BotLog.add("Ошибка запуска: " + t);
            toast("Не удалось запустить hh.ru: " + t.getMessage());
        }
        refreshLog();
    }

    private void stopScenario() {
        prefs().edit().putBoolean(HhAccessibilityService.KEY_PENDING, false).apply();
        BotLog.add("Остановлено пользователем");
        toast("Сценарий остановлен");
        refreshLog();
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void refreshLog() {
        long version = BotLog.version();
        if (version == lastLogVersion) {
            return;
        }
        lastLogVersion = version;
        logView.setText(BotLog.text());
        logScroll.post(new Runnable() {
            @Override
            public void run() {
                logScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
