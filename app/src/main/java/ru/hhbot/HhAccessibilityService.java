package ru.hhbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * «Selenium для Android»: служба доступности, которая находит окно hh.ru,
 * открывает экран поиска, посимвольно вводит запрос и нажимает «Найти».
 */
public class HhAccessibilityService extends AccessibilityService {

    private static final String TAG = "HhBot";

    static final String PREFS = "hhbot";
    static final String KEY_PENDING = "pending";
    static final String KEY_PACKAGE = "target_package";
    static final String KEY_QUERY = "query";
    static final String KEY_STARTED = "started_at";
    static final String DEFAULT_QUERY = "embedded-программист";

    private static final int PHASE_WAIT_APP = 0;
    private static final int PHASE_PREPARE = 1;
    private static final int PHASE_WAIT_SEARCH_SCREEN = 2;
    private static final int PHASE_TYPE = 3;
    private static final int PHASE_SUBMIT = 4;

    private static final long TASK_TIMEOUT_MS = 90_000L;
    private static final long STALE_TASK_MS = 3 * 60_000L;

    private static final String[] SEARCH_WORDS = {
            "поиск", "найти", "искать", "search", "вакансии"
    };

    /** Слова именно для кнопки запуска поиска (без «Вакансии», чтобы не нажать фильтр). */
    private static final String[] SUBMIT_WORDS = {
            "найти", "искать", "поиск", "search"
    };

    /** Подсказки, по которым узнаётся строка поиска на главном экране. */
    private static final String[] ENTRY_HINT_WORDS = {
            "должность", "ключевые слова", "профессия", "компания", "компании"
    };

    private static HhAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private Task task;
    private boolean tickQueued;
    private boolean stepQueued;

    public static HhAccessibilityService getInstance() {
        return instance;
    }

    private static final class Task {
        String pkg;
        String query;
        long startedAtWall;
        long startedAtUptime;
        int phase = PHASE_WAIT_APP;
        long phaseSince = SystemClock.uptimeMillis();
        long submitAt;
        AccessibilityNodeInfo field;
        int typeIndex;
        int typeFailures;
        int entryAttempts;
        int backAttempts;
        int onboardClicks;
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tickQueued = false;
            pickUpPendingTask();
            step();
            scheduleTick(task == null ? 500 : 300);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        BotLog.add("Служба доступности подключена");
        scheduleTick(200);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (task == null) {
            pickUpPendingTask();
        }
        if (task != null && event != null) {
            CharSequence pkg = event.getPackageName();
            if (pkg != null && task.pkg.equals(pkg.toString())) {
                scheduleStep(120);
            }
        }
    }

    @Override
    public void onInterrupt() {
        // не требуется
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        instance = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Постановка задачи
    // ------------------------------------------------------------------

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void pickUpPendingTask() {
        SharedPreferences sp = prefs();
        if (!sp.getBoolean(KEY_PENDING, false)) {
            if (task != null) {
                BotLog.add("Задача отменена пользователем");
                task = null;
            }
            return;
        }

        long startedAt = sp.getLong(KEY_STARTED, 0);
        if (startedAt <= 0 || System.currentTimeMillis() - startedAt > STALE_TASK_MS) {
            sp.edit().putBoolean(KEY_PENDING, false).apply();
            return;
        }

        if (task != null && task.startedAtWall == startedAt) {
            return;
        }

        String pkg = sp.getString(KEY_PACKAGE, "");
        String query = sp.getString(KEY_QUERY, DEFAULT_QUERY);
        if (TextUtils.isEmpty(pkg)) {
            BotLog.add("Ошибка: не задан пакет hh.ru");
            sp.edit().putBoolean(KEY_PENDING, false).apply();
            return;
        }
        if (TextUtils.isEmpty(query)) {
            query = DEFAULT_QUERY;
        }

        task = new Task();
        task.pkg = pkg;
        task.query = query;
        task.startedAtWall = startedAt;
        task.startedAtUptime = SystemClock.uptimeMillis();
        task.phase = PHASE_WAIT_APP;
        task.phaseSince = task.startedAtUptime;
        BotLog.add("Новая задача: «" + query + "» в " + pkg);
    }

    private void finishTask(String message) {
        BotLog.add(message);
        SharedPreferences sp = prefs();
        sp.edit().putBoolean(KEY_PENDING, false).apply();
        task = null;
        try {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private void failTask(String message) {
        finishTask("❌ " + message);
    }

    // ------------------------------------------------------------------
    // Планировщик
    // ------------------------------------------------------------------

    private void scheduleTick(long delayMs) {
        if (tickQueued) {
            return;
        }
        tickQueued = true;
        handler.postDelayed(ticker, delayMs);
    }

    private void scheduleStep(long delayMs) {
        if (stepQueued) {
            return;
        }
        stepQueued = true;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stepQueued = false;
                step();
            }
        }, delayMs);
    }

    // ------------------------------------------------------------------
    // Автомат состояний
    // ------------------------------------------------------------------

    private void step() {
        try {
            stepInternal();
        } catch (Throwable t) {
            BotLog.add("Внутренняя ошибка автоматизации: " + t);
        }
    }

    private void stepInternal() {
        if (task == null) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (now - task.startedAtUptime > TASK_TIMEOUT_MS) {
            failTask("Таймаут: не удалось выполнить сценарий за "
                    + (TASK_TIMEOUT_MS / 1000) + " секунд");
            return;
        }

        AccessibilityNodeInfo root = findTargetRoot();
        if (root == null) {
            if (task.phase != PHASE_WAIT_APP) {
                BotLog.add("Окно hh.ru не видно (свернуто?), жду...");
                task.phase = PHASE_WAIT_APP;
                task.phaseSince = now;
            }
            return;
        }

        switch (task.phase) {
            case PHASE_WAIT_APP:
                BotLog.add("Окно hh.ru найдено — начинаю искать строку поиска");
                task.phase = PHASE_PREPARE;
                task.phaseSince = now;
                step(); // сразу пробуем следующий шаг
                break;

            case PHASE_PREPARE:
                prepare(root, now);
                break;

            case PHASE_WAIT_SEARCH_SCREEN:
                waitSearchScreen(root, now);
                break;

            case PHASE_TYPE:
                // вводом управляет отдельный runnable
                break;

            case PHASE_SUBMIT:
                submit(root, now);
                break;

            default:
                break;
        }
    }

    private void prepare(AccessibilityNodeInfo root, long now) {
        List<AccessibilityNodeInfo> nodes = flatten(root, 3500);

        // 1. Пропускаем онбординг/рекламные шторки, если они есть.
        if (task.onboardClicks < 3) {
            AccessibilityNodeInfo skip = findOnboarding(nodes);
            if (skip != null) {
                task.onboardClicks++;
                String label = describe(skip);
                if (click(skip)) {
                    BotLog.add("Закрываю стартовый экран: «" + label + "»");
                    return;
                }
            }
        }

        // 2. Ищем само поле ввода.
        AccessibilityNodeInfo field = findSearchField(nodes);
        if (field != null) {
            task.entryAttempts = 0;
            beginTyping(field);
            return;
        }

        // 3. Поля нет — возможно, мы на главном экране. Открываем поиск.
        AccessibilityNodeInfo entry = findSearchEntry(nodes);
        if (entry != null) {
            String label = describe(entry);
            if (click(entry)) {
                task.entryAttempts = 0;
                BotLog.add("Открываю экран поиска (нажал «" + label + "»)");
                task.phase = PHASE_WAIT_SEARCH_SCREEN;
                task.phaseSince = now;
                return;
            }
        }

        task.entryAttempts++;

        // 4. Мы на внутреннем экране hh.ru (результаты поиска, вакансия и т.п.) —
        //    возвращаемся назад, чтобы добраться до строки поиска.
        if (task.entryAttempts >= 4 && task.backAttempts < 3) {
            AccessibilityNodeInfo back = findBackButton(nodes);
            if (back != null) {
                task.backAttempts++;
                task.entryAttempts = 0;
                BotLog.add("Открыт внутренний экран hh.ru — возвращаюсь назад ("
                        + task.backAttempts + "/3)");
                if (click(back)) {
                    return;
                }
                performGlobalAction(GLOBAL_ACTION_BACK);
                return;
            }
        }

        if (task.entryAttempts == 4 || task.entryAttempts == 20) {
            BotLog.add("Поле/кнопка поиска пока не найдены, продолжаю искать...");
        }
        if (task.entryAttempts > 60) {
            failTask("Не нашёл строку поиска в приложении hh.ru. "
                    + "Откройте главный экран hh.ru и нажмите «Запустить» ещё раз.");
        }
    }

    private void waitSearchScreen(AccessibilityNodeInfo root, long now) {
        List<AccessibilityNodeInfo> nodes = flatten(root, 3500);
        AccessibilityNodeInfo field = findSearchField(nodes);
        if (field != null) {
            beginTyping(field);
            return;
        }
        if (now - task.phaseSince > 6000) {
            BotLog.add("Экран поиска не открылся, пробую снова");
            task.phase = PHASE_PREPARE;
            task.phaseSince = now;
        }
    }

    private void submit(AccessibilityNodeInfo root, long now) {
        if (now < task.submitAt) {
            return;
        }

        List<AccessibilityNodeInfo> nodes = flatten(root, 3500);

        // На hh.ru отдельной кнопки «Найти» нет: поиск запускается тапом
        // по первой подсказке с введённым запросом. Важно не попасть в
        // подсказку «искать в названиях компаний» — она включает фильтр
        // «В названии компании» и обнуляет выдачу.
        AccessibilityNodeInfo suggestion = findQuerySuggestion(nodes, task.query, task.field);
        if (suggestion != null) {
            if (click(suggestion)) {
                finishTask("✅ Готово: введено «" + task.query
                        + "» и запущен поиск по подсказке");
                return;
            }
        }

        // На случай экранов с настоящей кнопкой «Найти».
        AccessibilityNodeInfo button = findSubmitButton(nodes, task.field);
        if (button != null) {
            String label = describe(button);
            if (click(button)) {
                finishTask("✅ Готово: введено «" + task.query
                        + "» и нажата кнопка «" + label + "»");
                return;
            }
        }

        // Запасной вариант для Android 11+: действие IME «Поиск/Enter».
        if (Build.VERSION.SDK_INT >= 30 && task.field != null) {
            try {
                int imeEnter = AccessibilityNodeInfo.AccessibilityAction
                        .ACTION_IME_ENTER.getId();
                if (task.field.performAction(imeEnter)) {
                    finishTask("✅ Готово: введено «" + task.query
                            + "», поиск запущен через клавишу ввода");
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        // Подсказки появляются с задержкой (сеть), поэтому повторяем попытки
        // по времени, а не по количеству событий.
        if (now - task.phaseSince > 12_000L) {
            finishTask("⚠ Текст «" + task.query + "» введён, но строку поиска "
                    + "запустить не удалось. Нажмите поиск вручную.");
            return;
        }
        task.submitAt = now + 500;
        scheduleStep(500);
    }

    // ------------------------------------------------------------------
    // Ввод текста «как человек»: по одному символу с паузами
    // ------------------------------------------------------------------

    private void beginTyping(AccessibilityNodeInfo field) {
        task.field = field;
        task.typeIndex = 0;
        task.typeFailures = 0;
        task.phase = PHASE_TYPE;
        task.phaseSince = SystemClock.uptimeMillis();

        Bundle clear = new Bundle();
        clear.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
        try {
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clear);
        } catch (Throwable ignored) {
        }

        BotLog.add("Поле поиска найдено — печатаю «" + task.query + "»");
        handler.post(typingRunnable);
    }

    private final Runnable typingRunnable = new Runnable() {
        @Override
        public void run() {
            if (task == null || task.phase != PHASE_TYPE) {
                return;
            }
            if (SystemClock.uptimeMillis() - task.phaseSince > 30_000L) {
                failTask("Таймаут ввода текста");
                return;
            }

            AccessibilityNodeInfo field = task.field;
            if (field == null) {
                failTask("Поле ввода пропало");
                return;
            }

            // Если узел «устарел» после перерисовки экрана — находим поле заново.
            if (task.typeFailures >= 3) {
                AccessibilityNodeInfo root = findTargetRoot();
                if (root != null) {
                    AccessibilityNodeInfo fresh = findSearchField(flatten(root, 3500));
                    if (fresh != null) {
                        task.field = fresh;
                        field = fresh;
                    }
                }
            }

            int next = Math.min(task.typeIndex + 1, task.query.length());
            String part = task.query.substring(0, next);

            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, part);

            boolean ok;
            try {
                field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            } catch (Throwable t) {
                ok = false;
            }

            if (!ok) {
                task.typeFailures++;
                if (task.typeFailures > 8) {
                    failTask("Не удалось ввести текст в поле поиска");
                    return;
                }
                handler.postDelayed(this, 250);
                return;
            }

            task.typeFailures = 0;
            task.typeIndex = next;

            if (next < task.query.length()) {
                if (next == 1 || next % 8 == 0) {
                    BotLog.add("Ввод: " + part);
                }
                handler.postDelayed(this, 70 + random.nextInt(90));
            } else {
                task.phase = PHASE_SUBMIT;
                task.phaseSince = SystemClock.uptimeMillis();
                task.submitAt = task.phaseSince + 600;
                BotLog.add("Текст введён: «" + task.query + "»");
                scheduleStep(600);
            }
        }
    };

    // ------------------------------------------------------------------
    // Поиск узлов
    // ------------------------------------------------------------------

    private AccessibilityNodeInfo findTargetRoot() {
        if (task == null) {
            return null;
        }
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && task.pkg.equals(String.valueOf(root.getPackageName()))) {
                return root;
            }
        } catch (Throwable ignored) {
        }
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null && task.pkg.equals(String.valueOf(root.getPackageName()))) {
                    return root;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static List<AccessibilityNodeInfo> flatten(AccessibilityNodeInfo root, int max) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        collect(root, out, 0, max);
        return out;
    }

    private static void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out,
                                int depth, int max) {
        if (node == null || out.size() >= max || depth > 50) {
            return;
        }
        out.add(node);
        int count = node.getChildCount();
        for (int i = 0; i < count && out.size() < max; i++) {
            try {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    collect(child, out, depth + 1, max);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private AccessibilityNodeInfo findSearchField(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo fallback = null;
        for (AccessibilityNodeInfo node : nodes) {
            if (!isEditField(node) || !isVisible(node)) {
                continue;
            }
            String text = allText(node);
            if (text.contains("професс") || text.contains("должност")
                    || text.contains("компани") || text.contains("поиск")
                    || text.contains("search") || text.contains("ваканс")
                    || text.contains("ключев") || text.contains("навык")) {
                return node;
            }
            // Не принимаем за поиск поля входа/регистрации.
            boolean loginField = text.contains("почт") || text.contains("mail")
                    || text.contains("пароль") || text.contains("password")
                    || text.contains("телефон") || text.contains("phone")
                    || text.contains("логин") || text.contains("login")
                    || text.contains("имя") || text.contains("фамили");
            if (!loginField && fallback == null) {
                fallback = node;
            }
        }
        return fallback;
    }

    private AccessibilityNodeInfo findSearchEntry(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo best = null;
        int bestScore = 0;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        long screenArea = (long) screenHeight * screenWidth;

        for (AccessibilityNodeInfo node : nodes) {
            if (isEditField(node)) {
                continue;
            }
            String text = lower(node.getText());
            String desc = lower(node.getContentDescription());
            String id = lower(node.getViewIdResourceName());
            if (text.isEmpty() && desc.isEmpty() && !id.contains("search")) {
                continue;
            }

            // Compose часто отдаёт текст в дочернем TextView, а кликабелен родитель.
            AccessibilityNodeInfo clickable = node.isClickable()
                    ? node : clickableAncestor(node, 6);
            if (clickable == null || !isVisible(clickable) || isEditField(clickable)) {
                continue;
            }

            int score = 0;
            if (containsWord(text, ENTRY_HINT_WORDS) || containsWord(desc, ENTRY_HINT_WORDS)) {
                score = 120; // «Должность, ключевые слова» — это точно строка поиска
            } else if (text.contains("результат") || desc.contains("результат")) {
                score = 0; // заголовок «Результаты поиска» — это не строка ввода
            } else if (isExactSearchWord(text) || isExactSearchWord(desc)) {
                score = 90;
            } else if (startsWithSearchWord(text) || startsWithSearchWord(desc)) {
                score = 80;
            } else if (containsSearchWord(text) || containsSearchWord(desc)) {
                score = 65;
            } else if (id.contains("search")) {
                score = 50;
            }
            if (score == 0) {
                continue;
            }
            if (text.length() > 60) {
                score -= 40;
            }

            Rect bounds = new Rect();
            clickable.getBoundsInScreen(bounds);
            // Нижняя навигация («Поиск»/«Вакансии») — не то, что нам нужно.
            if (screenHeight > 0 && bounds.exactCenterY() > screenHeight * 0.88f) {
                score -= 80;
            }
            long area = (long) bounds.width() * bounds.height();
            if (screenArea > 0 && area > screenArea / 2) {
                score -= 100; // не кликаем по контейнеру во весь экран
            }

            if (score > bestScore) {
                bestScore = score;
                best = clickable;
            }
        }
        return best;
    }

    private AccessibilityNodeInfo findSubmitButton(List<AccessibilityNodeInfo> nodes,
                                                   AccessibilityNodeInfo field) {
        Rect fieldBounds = null;
        if (field != null) {
            fieldBounds = new Rect();
            field.getBoundsInScreen(fieldBounds);
        }

        AccessibilityNodeInfo best = null;
        int bestScore = 0;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        for (AccessibilityNodeInfo node : nodes) {
            if (isEditField(node)) {
                continue;
            }
            if (node.equals(field)) {
                continue;
            }

            String text = lower(node.getText());
            String desc = lower(node.getContentDescription());
            if (text.isEmpty() && desc.isEmpty()) {
                continue;
            }

            AccessibilityNodeInfo clickable = node.isClickable()
                    ? node : clickableAncestor(node, 6);
            if (clickable == null || !isVisible(clickable) || isEditField(clickable)) {
                continue;
            }

            int score = 0;
            if (isExactWord(text, SUBMIT_WORDS) || isExactWord(desc, SUBMIT_WORDS)) {
                score = 110;
            } else if (startsWithWord(text, SUBMIT_WORDS) || startsWithWord(desc, SUBMIT_WORDS)) {
                score = 95;
            } else if (containsWord(text, SUBMIT_WORDS) || containsWord(desc, SUBMIT_WORDS)) {
                score = 80;
            }
            if (score == 0) {
                continue;
            }

            Rect bounds = new Rect();
            clickable.getBoundsInScreen(bounds);
            // Не кликаем по большому контейнеру, внутри которого лежит само поле.
            if (fieldBounds != null && fieldBounds.width() > 0
                    && bounds.contains(fieldBounds)) {
                continue;
            }
            // Кнопка «Найти» не может быть выше поля ввода (там заголовок/бэк).
            if (fieldBounds != null && fieldBounds.height() > 0
                    && bounds.exactCenterY() < fieldBounds.top) {
                continue;
            }
            // Не нажимаем нижнюю навигацию вместо кнопки.
            if (screenHeight > 0 && bounds.exactCenterY() > screenHeight * 0.88f) {
                score -= 60;
            }
            if (text.length() > 70) {
                score -= 40;
            }
            if (score > bestScore) {
                bestScore = score;
                best = clickable;
            }
        }
        return best;
    }

    /**
     * Подсказка/строка автодополнения с введённым запросом. На hh.ru тап по ней
     * запускает поиск (отдельной кнопки «Найти» в приложении нет).
     */
    private AccessibilityNodeInfo findQuerySuggestion(List<AccessibilityNodeInfo> nodes,
                                                      String query,
                                                      AccessibilityNodeInfo field) {
        String q = lower(query);
        if (q.isEmpty()) {
            return null;
        }
        Rect fieldBounds = null;
        if (field != null) {
            fieldBounds = new Rect();
            field.getBoundsInScreen(fieldBounds);
        }

        AccessibilityNodeInfo best = null;
        int bestTop = Integer.MAX_VALUE;

        for (AccessibilityNodeInfo node : nodes) {
            if (isEditField(node) || !isVisible(node)) {
                continue;
            }
            String text = lower(node.getText());
            if (text.isEmpty()) {
                continue;
            }
            boolean matches = text.equals(q)
                    || (text.startsWith(q) && text.length() <= q.length() + 30);
            if (!matches) {
                continue;
            }

            AccessibilityNodeInfo clickable = node.isClickable()
                    ? node : clickableAncestor(node, 6);
            if (clickable == null || !isVisible(clickable) || isEditField(clickable)) {
                continue;
            }

            Rect bounds = new Rect();
            clickable.getBoundsInScreen(bounds);
            // Подсказка находится под полем ввода, а не над ним и не в самом поле.
            if (fieldBounds != null && bounds.exactCenterY() <= fieldBounds.bottom) {
                continue;
            }
            if (bounds.height() > 400) {
                continue;
            }
            // hh.ru добавляет вторую подсказку «искать в названиях компаний».
            // Она включает фильтр «В названии компании» — пропускаем её.
            if (isCompanySuggestion(clickable)) {
                Log.i(TAG, "Пропускаю подсказку 'в названиях компаний' " + bounds);
                continue;
            }
            if (bounds.top < bestTop) {
                bestTop = bounds.top;
                best = clickable;
            }
        }
        return best;
    }

    /** Подсказка «искать в названиях компаний» (включает фильтр по компании). */
    private static boolean isCompanySuggestion(AccessibilityNodeInfo row) {
        String text = subtreeText(row, 0);
        return text.contains("названиях компаний")
                || text.contains("названии компании")
                || text.contains("название компании")
                || text.contains("названия компании");
    }

    /** Текст самого узла и его потомков (для Compose-строк с подзаголовком). */
    private static String subtreeText(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 4) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(lower(node.getText())).append(' ');
        sb.append(lower(node.getContentDescription())).append(' ');
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            try {
                sb.append(subtreeText(node.getChild(i), depth + 1)).append(' ');
            } catch (Throwable ignored) {
            }
        }
        return sb.toString();
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node,
                                                           int maxLevels) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i <= maxLevels && current != null; i++) {
            try {
                if (current.isClickable() && current.isVisibleToUser()) {
                    return current;
                }
                current = current.getParent();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findBackButton(List<AccessibilityNodeInfo> nodes) {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        for (AccessibilityNodeInfo node : nodes) {
            if (!isVisible(node)) {
                continue;
            }
            String text = lower(node.getText());
            String desc = lower(node.getContentDescription());
            boolean isBack = text.equals("назад") || desc.equals("назад")
                    || text.equals("back") || desc.equals("back")
                    || desc.contains("стрелка назад");
            if (!isBack) {
                continue;
            }
            AccessibilityNodeInfo clickable = node.isClickable()
                    ? node : clickableAncestor(node, 6);
            AccessibilityNodeInfo target = clickable != null ? clickable : node;
            if (!isVisible(target)) {
                continue;
            }
            Rect bounds = new Rect();
            target.getBoundsInScreen(bounds);
            // Кнопка «Назад» — в верхней части экрана.
            if (screenHeight > 0 && bounds.exactCenterY() < screenHeight * 0.25f) {
                return target;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findOnboarding(List<AccessibilityNodeInfo> nodes) {
        String[] labels = {
                "пропустить", "позже", "не сейчас", "закрыть", "понятно", "пропуск"
        };
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isClickable() || !isVisible(node)) {
                continue;
            }
            String text = lower(node.getText());
            String desc = lower(node.getContentDescription());
            for (String label : labels) {
                if (text.equals(label) || desc.equals(label)) {
                    return node;
                }
            }
        }
        return null;
    }

    private static boolean isEditField(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        try {
            if (node.isEditable()) {
                return true;
            }
            CharSequence cls = node.getClassName();
            return cls != null && cls.toString().contains("EditText");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isVisible(AccessibilityNodeInfo node) {
        try {
            if (!node.isVisibleToUser()) {
                return false;
            }
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            return bounds.width() > 0 && bounds.height() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String allText(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        sb.append(lower(node.getText()));
        sb.append(' ');
        try {
            sb.append(lower(node.getHintText()));
        } catch (Throwable ignored) {
        }
        sb.append(' ');
        sb.append(lower(node.getContentDescription()));
        return sb.toString().trim();
    }

    private static String describe(AccessibilityNodeInfo node) {
        String text = lower(node.getText());
        if (!TextUtils.isEmpty(text)) {
            return text;
        }
        String desc = lower(node.getContentDescription());
        if (!TextUtils.isEmpty(desc)) {
            return desc;
        }
        String id = lower(node.getViewIdResourceName());
        return TextUtils.isEmpty(id) ? "без названия" : id;
    }

    private static String lower(CharSequence value) {
        return value == null ? "" : value.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isExactSearchWord(String value) {
        return isExactWord(value, SEARCH_WORDS)
                || "поиск вакансий".equals(value)
                || "найти вакансии".equals(value)
                || "искать вакансии".equals(value);
    }

    private static boolean startsWithSearchWord(String value) {
        return startsWithWord(value, SEARCH_WORDS);
    }

    private static boolean containsSearchWord(String value) {
        return containsWord(value, SEARCH_WORDS);
    }

    private static boolean isExactWord(String value, String[] words) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        for (String word : words) {
            if (value.equals(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithWord(String value, String[] words) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        for (String word : words) {
            if (value.startsWith(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWord(String value, String[] words) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        for (String word : words) {
            if (value.contains(word)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Клики
    // ------------------------------------------------------------------

    private boolean click(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        try {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            Log.i(TAG, "Клик: " + node.getClassName()
                    + " id=" + node.getViewIdResourceName()
                    + " text=[" + node.getText() + "]"
                    + " desc=[" + node.getContentDescription() + "] " + b);
        } catch (Throwable ignored) {
        }
        try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        // Иногда кликабелен не сам узел, а его родитель.
        AccessibilityNodeInfo parent = node;
        for (int i = 0; i < 3 && parent != null; i++) {
            try {
                parent = parent.getParent();
                if (parent != null && parent.isClickable()
                        && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }

        // Последний вариант — «человеческий» тап по центру элемента.
        return tapCenter(node);
    }

    private boolean tapCenter(AccessibilityNodeInfo node) {
        try {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                return false;
            }
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                    .build();
            return dispatchGesture(gesture, null, null);
        } catch (Throwable t) {
            return false;
        }
    }
}
