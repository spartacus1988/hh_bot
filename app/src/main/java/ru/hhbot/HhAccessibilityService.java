package ru.hhbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

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

    // --- Цикл по вакансиям ---
    static final String KEY_LOOP_PENDING = "loop_pending";
    static final String KEY_LOOP_COUNT = "loop_count";
    static final String KEY_LOOP_INDEX = "loop_index";
    static final String KEY_RESUME_TEXT = "resume_text";
    static final String KEY_CLIPBOARD = "clipboard_text";
    static final String KEY_CLIPBOARD_AT = "clipboard_at";
    static final String KEY_CLIPBOARD_PURPOSE = "clipboard_purpose";
    static final String KEY_CHATBOX_PKG = "chatbox_pkg";
    static final String KEY_HH_ACTIVITY = "hh_activity";
    static final String KEY_LOOP_PAUSE_MS = "loop_pause_ms";
    /** Вакансии, на которые уже откликнулись (чтобы не открывать их снова). */
    static final String KEY_RESPONDED = "responded_keys";
    static final String KEY_RESUME_PENDING = "resume_pending";

    static final String DEFAULT_CHATBOX_PKG = "xyz.chatboxapp.chatbox";
    static final String DEFAULT_HH_ACTIVITY =
            "ru.hh.android/.feature.root.ApplicantRootActivity";

    static final String PROMPT_TEXT =
            "Сформулируй сопроводительное письмо исходя из описания вакансии и резюме.";

    /** Уточнение формата: нужно письмо, а не рассуждения модели. */
    static final String PROMPT_FORMAT =
            "Пиши готовый текст: сначала строка «Тема: …», затем письмо с нового абзаца. "
                    + "Без заглушек вида [Имя] и без пояснений.";

    private static final int LP_WAIT_LIST = 0;
    private static final int LP_OPEN_VACANCY = 1;
    private static final int LP_WAIT_VACANCY = 2;
    private static final int LP_COPY_DESC = 3;
    private static final int LP_WAIT_DESC = 4;
    private static final int LP_DS_OPEN = 5;
    private static final int LP_DS_SEND = 6;
    private static final int LP_DS_WAIT = 7;
    private static final int LP_COPY_ANSWER = 8;
    private static final int LP_WAIT_ANSWER = 9;
    private static final int LP_BACK_HH = 10;
    private static final int LP_RESPOND = 11;
    private static final int LP_RESPOND_FILL = 12;
    private static final int LP_RESPOND_SEND = 13;
    private static final int LP_SCAN_DESC = 14;
    private static final int LP_NEXT = 15;

    private static final int PHASE_WAIT_APP = 0;
    private static final int PHASE_PREPARE = 1;
    private static final int PHASE_WAIT_SEARCH_SCREEN = 2;
    private static final int PHASE_TYPE = 3;
    private static final int PHASE_SUBMIT = 4;

    // --- Автозахват резюме из hh.ru ---
    private static final int RP_WAIT_HH = 0;
    private static final int RP_PROFILE = 1;
    private static final int RP_OPEN_RESUME = 2;
    private static final int RP_WAIT_RESUME = 3;
    private static final int RP_COLLECT = 4;
    private static final int RP_FINISH = 5;

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

    // Состояние цикла по вакансиям.
    private boolean loopActive;
    private int lpPhase = LP_WAIT_LIST;
    private long lpPhaseSince;
    private long lpNextActionAt;
    private int lpCount;
    private int lpIndex;
    private int lpAttempts;
    private int lpBackAttempts;
    private String lpDescription = "";
    private String lpAnswer = "";
    private String lpCurrentKey = "";
    private String lpCurrentTitle = "";
    private long lpLastClipboardAt;
    private final Set<String> lpDoneKeys = new HashSet<>();
    /** Ключи, о пропуске которых уже написали в лог. */
    private final Set<String> lpSkipLogged = new HashSet<>();
    private long lpAnswerStableSince;
    private boolean lpSawGenerating;
    private long lpSentAt;
    private String lpSentMessage = "";
    private boolean lpLetterInserted;
    private int lpDescRetries;
    private String lpAnswerCandidate = "";
    private final List<String> lpScanLines = new ArrayList<>();
    private final Set<String> lpScanSeen = new HashSet<>();
    private int lpScanNoNew;
    private int lpScanScrolls;
    /** Встретили блок «Похожие вакансии» — дальше описание не читаем. */
    private boolean lpScanStop;
    /** Нижняя граница последней открытой карточки — чтобы идти строго вниз по списку. */
    private int lpLastCardBottom;
    /** Сколько раз нажимали «Ещё вакансии» без найденных новых карточек. */
    private int lpMoreTries;
    /** Когда последний раз нажимали «Ещё вакансии», чтобы не спамить кнопку. */
    private long lpMoreAt;
    /** Клик привёл на рекламу: после возврата к списку карточку пропускаем. */
    private boolean lpAdDetour;
    /** Карточка под курсором и время, когда её впервые увидели. */
    private String lpPendingCardKey = "";
    private long lpPendingCardAt;
    /** Закрываем встроенный браузер с рекламой курсов. */
    private boolean lpAdExit;
    /** Не нажимать «Назад» в начале LP_NEXT: мы уже вернулись к списку. */
    private boolean lpSkipNextBack;
    /** Запрос в Chatbox уже переотправляли для этой вакансии. */
    private boolean lpResent;
    /** Chatbox открыт без ACTION_SEND — запрос нужно вставить в поле ввода. */
    private boolean lpNewChat;
    /** Приложение Chatbox уже запускали для этой вакансии. */
    private boolean lpDsLaunched;
    /** Запрос уже отдан Chatbox через «Поделиться». */
    private boolean lpShareSent;
    /** Повторная вставка запроса, когда поле осталось пустым. */
    private boolean lpShareRetry;
    /** Сколько раз вставляли запрос в поле Chatbox и когда — последний раз. */
    private int lpInsertCount;
    private long lpInsertAt;
    /** Сколько раз пытались нажать «Отправить» в Chatbox. */
    private int lpSendTries;
    /** Клавиатура спрятана перед нажатием «Отправить». */
    private boolean lpKeyboardHidden;
    /** Когда последний раз нажимали «Отправить» в Chatbox. */
    private long lpSendAt;
    /** Сколько раз пытались открыть новый чат Chatbox. */
    private int lpNewChatTries;
    /** Последний разобранный ответ начинался с «Тема:» или заголовка. */
    private boolean lpAnswerHadHeader;
    /** Держит экран включённым, пока идёт цикл. */
    private PowerManager.WakeLock lpWakeLock;

    // Состояние автозахвата резюме.
    private boolean resumeActive;
    private int rpPhase = RP_WAIT_HH;
    private int rpAttempts;
    private long rpNextActionAt;
    private int rpScrolls;
    private int rpNoNew;
    private final List<String> rpLines = new ArrayList<>();
    private final Set<String> rpSeen = new HashSet<>();

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
        long nextActionAt;
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tickQueued = false;
            if (!loopActive) {
                pickUpResumeTask();
            }
            if (resumeActive) {
                resumeStep();
                scheduleTick(250);
                return;
            }
            if (!loopActive) {
                pickUpPendingTask();
                step();
            }
            pickUpLoopTask();
            if (loopActive) {
                loopStep();
            }
            scheduleTick(loopActive ? 350 : (task == null ? 500 : 300));
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
        releaseLoopWakeLock();
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
        // Поиск не удался: цикл по вакансиям запускать нельзя, иначе он
        // начнётся на случайном экране hh.ru.
        prefs().edit().putBoolean(KEY_LOOP_PENDING, false).apply();
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
        if (task.nextActionAt > 0) {
            if (now < task.nextActionAt) {
                return;
            }
            task.nextActionAt = 0;
        }
        List<AccessibilityNodeInfo> nodes = flatten(root, 3500);

        // 0. Поверх экрана может висеть модальное окно hh.ru.
        if (hasAnyText(root, new String[]{"какой формат удобнее",
                "сохранить и продолжить", "укажите место"})) {
            if (task.entryAttempts % 3 == 0) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                BotLog.add("Закрываю всплывающее окно hh.ru");
                task.nextActionAt = now + 1200;
            }
            task.entryAttempts++;
            return;
        }

        // 1. Пропускаем онбординг/рекламные шторки, если они есть.
        if (task.onboardClicks < 3) {
            AccessibilityNodeInfo skip = findOnboarding(nodes);
            if (skip != null) {
                task.onboardClicks++;
                String label = describe(skip);
                if (click(skip)) {
                    BotLog.add("Закрываю стартовый экран: «" + label + "»");
                    task.nextActionAt = now + 1000;
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

        // 3. Мы на внутреннем экране hh.ru (вакансия, профиль, результаты
        //    поиска) — возвращаемся назад, чтобы добраться до главного экрана.
        AccessibilityNodeInfo back = findBackButton(nodes);
        if (back != null && task.backAttempts < 12) {
            task.backAttempts++;
            task.entryAttempts = 0;
            BotLog.add("Открыт внутренний экран hh.ru — возвращаюсь назад ("
                    + task.backAttempts + "/12)");
            if (!click(back)) {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
            task.nextActionAt = now + 1500;
            return;
        }

        // 4. Поля нет — возможно, мы на главном экране. Открываем поиск.
        AccessibilityNodeInfo entry = findSearchEntry(nodes);
        if (entry != null) {
            String label = describe(entry);
            if (click(entry)) {
                task.entryAttempts = 0;
                BotLog.add("Открываю экран поиска (нажал «" + label + "»)");
                task.phase = PHASE_WAIT_SEARCH_SCREEN;
                task.phaseSince = now;
                task.nextActionAt = now + 1200;
                return;
            }
        }

        task.entryAttempts++;

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

    // ==================================================================
    // Автозахват резюме из hh.ru
    // ==================================================================

    private void pickUpResumeTask() {
        SharedPreferences sp = prefs();
        if (!sp.getBoolean(KEY_RESUME_PENDING, false)) {
            if (resumeActive) {
                BotLog.add("Захват резюме остановлен");
                resumeActive = false;
            }
            return;
        }
        if (resumeActive || task != null) {
            return;
        }

        long startedAt = sp.getLong(KEY_STARTED, 0);
        if (startedAt <= 0 || System.currentTimeMillis() - startedAt > STALE_TASK_MS) {
            sp.edit().putBoolean(KEY_RESUME_PENDING, false).apply();
            return;
        }

        resumeActive = true;
        rpPhase = RP_WAIT_HH;
        rpAttempts = 0;
        rpNextActionAt = 0;
        rpScrolls = 0;
        rpNoNew = 0;
        rpLines.clear();
        rpSeen.clear();
        BotLog.add("▶ Захват резюме из hh.ru");
        bringHhToFront();
        try {
            Toast.makeText(this, "Собираю резюме из hh.ru...", Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private void setResumePhase(int phase) {
        rpPhase = phase;
        rpAttempts = 0;
        rpNextActionAt = 0;
    }

    private void pauseResume(long ms) {
        rpNextActionAt = SystemClock.uptimeMillis() + ms;
    }

    private void resumeStep() {
        try {
            resumeStepInternal();
        } catch (Throwable t) {
            BotLog.add("Ошибка захвата резюме: " + t);
        }
    }

    private void resumeStepInternal() {
        if (!resumeActive) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (rpNextActionAt > 0 && now < rpNextActionAt) {
            return;
        }
        rpNextActionAt = 0;
        rpAttempts++;

        switch (rpPhase) {
            case RP_WAIT_HH: rpWaitHh(); break;
            case RP_PROFILE: rpProfile(); break;
            case RP_OPEN_RESUME: rpOpenResume(); break;
            case RP_WAIT_RESUME: rpWaitResume(); break;
            case RP_COLLECT: rpCollect(); break;
            default: break;
        }
    }

    private void rpWaitHh() {
        String pkg = prefs().getString(KEY_PACKAGE, "ru.hh.android");
        AccessibilityNodeInfo root = findRootFor(pkg);
        if (root == null) {
            if (rpAttempts % 8 == 0) {
                bringHhToFront();
            }
            if (rpAttempts > 120) {
                finishResume("⚠ Не удалось открыть hh.ru для захвата резюме");
            }
            return;
        }
        if (hasAnyText(root, new String[]{"мои резюме"})) {
            BotLog.add("Профиль hh.ru открыт");
            setResumePhase(RP_OPEN_RESUME);
            return;
        }
        // Нижней панели нет — значит открыт вложенный экран (вакансия, отклик).
        // Сначала закрываем его кнопкой «Назад».
        if (!hasBottomNavTabs(root)) {
            if (rpAttempts <= 20) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                pauseResume(1200);
                return;
            }
            BotLog.add("⚠ Не удалось закрыть вложенный экран hh.ru");
            finishResume("⚠ Не удалось открыть профиль hh.ru");
            return;
        }
        tapProfileTab();
        BotLog.add("Открываю вкладку «Профиль»");
        setResumePhase(RP_PROFILE);
        pauseResume(2000);
    }

    /** Есть ли на экране кликабельная нижняя панель вкладок. */
    private boolean hasBottomNavTabs(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        int h = screenHeight();
        int w = getResources().getDisplayMetrics().widthPixels;
        for (AccessibilityNodeInfo n : flatten(root, 7000)) {
            if (!n.isClickable()) {
                continue;
            }
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (b.height() > 0 && b.height() < h * 0.06f
                    && b.width() < w * 0.35f
                    && b.top > h * 0.92f) {
                return true;
            }
        }
        return false;
    }

    private void rpProfile() {
        String pkg = prefs().getString(KEY_PACKAGE, "ru.hh.android");
        AccessibilityNodeInfo root = findRootFor(pkg);
        if (root != null && hasAnyText(root, new String[]{"мои резюме"})) {
            setResumePhase(RP_OPEN_RESUME);
            return;
        }
        if (rpAttempts % 10 == 0) {
            tapProfileTab();
        }
        if (rpAttempts > 40) {
            logScreen(pkg, 25);
            finishResume("⚠ Не удалось открыть профиль hh.ru");
        }
    }

    private void rpOpenResume() {
        String pkg = prefs().getString(KEY_PACKAGE, "ru.hh.android");
        AccessibilityNodeInfo root = findRootFor(pkg);
        if (root == null) {
            if (rpAttempts % 8 == 0) {
                bringHhToFront();
            }
            if (rpAttempts > 40) {
                finishResume("⚠ Окно hh.ru пропало во время захвата резюме");
            }
            return;
        }

        AccessibilityNodeInfo card = findResumeCard(root);
        if (card != null) {
            Rect b = new Rect();
            card.getBoundsInScreen(b);
            tap(b.exactCenterX(), b.exactCenterY());
            BotLog.add("Открываю первое резюме: " + shorten(textOf(card), 60));
        } else {
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = screenHeight();
            tap(w * 0.45f, h * 0.82f);
            BotLog.add("Карточка резюме не найдена в дереве — нажимаю по координате");
        }
        setResumePhase(RP_WAIT_RESUME);
        pauseResume(2200);
    }

    private void rpWaitResume() {
        String pkg = prefs().getString(KEY_PACKAGE, "ru.hh.android");
        AccessibilityNodeInfo root = findRootFor(pkg);
        if (root == null) {
            return;
        }
        if (hasAnyText(root, new String[]{"опыт работы", "мобильный телефон",
                "электронная почта"}) || (hasAnyText(root, new String[]{"резюме"})
                && hasAnyText(root, new String[]{"редактировать"}))) {
            BotLog.add("Резюме открыто — собираю текст");
            rpLines.clear();
            rpSeen.clear();
            rpScrolls = 0;
            rpNoNew = 0;
            setResumePhase(RP_COLLECT);
            pauseResume(800);
            return;
        }
        if (rpAttempts % 10 == 0 && rpAttempts > 0) {
            // Возможно, нажали мимо карточки — пробуем ещё раз.
            setResumePhase(RP_OPEN_RESUME);
            pauseResume(1000);
            return;
        }
        if (rpAttempts > 40) {
            logScreen(pkg, 25);
            finishResume("⚠ Резюме не открылось");
        }
    }

    private void rpCollect() {
        String pkg = prefs().getString(KEY_PACKAGE, "ru.hh.android");
        AccessibilityNodeInfo root = findRootFor(pkg);
        if (root == null) {
            if (rpAttempts % 10 == 0) {
                bringHhToFront();
            }
            if (rpAttempts > 100) {
                finishResume("⚠ Окно hh.ru пропало во время сбора текста резюме");
            }
            return;
        }

        int before = rpLines.size();
        collectResumeLines(root);
        if (rpLines.size() > before) {
            rpNoNew = 0;
        } else {
            rpNoNew++;
        }

        if (rpNoNew >= 5 || rpScrolls >= 40) {
            finishResume(null);
            return;
        }

        int w = getResources().getDisplayMetrics().widthPixels;
        int h = screenHeight();
        swipe(w * 0.5f, h * 0.78f, w * 0.5f, h * 0.32f, 550);
        rpScrolls++;
        pauseResume(900);
    }

    private void collectResumeLines(AccessibilityNodeInfo root) {
        for (AccessibilityNodeInfo n : flatten(root, 7000)) {
            String text = n.getText() == null ? "" : n.getText().toString();
            if (text.trim().isEmpty() && n.getContentDescription() != null) {
                text = n.getContentDescription().toString();
            }
            if (text.trim().isEmpty()) {
                continue;
            }
            for (String raw : text.split("\\n")) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String low = lower(line);
                if (isResumeChrome(low)) {
                    continue;
                }
                if (rpSeen.add(low)) {
                    rpLines.add(line);
                }
            }
        }
    }

    private static boolean isResumeChrome(String low) {
        switch (low) {
            case "поиск":
            case "подработка":
            case "карьера":
            case "общение":
            case "профиль":
            case "назад":
            case "редактировать":
            case "резюме":
            case "мои резюме":
            case "создать резюме":
            case "все":
            case "поднять":
            case "поднять в поиске":
                return true;
            default:
                break;
        }
        return low.matches("^[\\d\\s+]+$");
    }

    private void finishResume(String error) {
        SharedPreferences sp = prefs();
        sp.edit().putBoolean(KEY_RESUME_PENDING, false).apply();
        resumeActive = false;

        if (error != null) {
            BotLog.add(error);
            try {
                Toast.makeText(this, error.replace("⚠ ", ""), Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (String line : rpLines) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        String text = sb.toString().trim();
        if (text.length() < 50) {
            BotLog.add("⚠ Не удалось собрать текст резюме (получено " + text.length() + " симв.)");
            logScreen(prefs().getString(KEY_PACKAGE, "ru.hh.android"), 30);
            try {
                Toast.makeText(this, "Не удалось собрать резюме — откройте его в hh.ru вручную",
                        Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
            return;
        }

        sp.edit().putString(KEY_RESUME_TEXT, text).apply();
        BotLog.add("✅ Резюме сохранено: " + text.length() + " симв., "
                + rpLines.size() + " строк");
        try {
            Toast.makeText(this, "Резюме собрано: " + text.length() + " симв.",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private void tapProfileTab() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = screenHeight();
        tap(w * 0.92f, h - 90);
    }

    /** Первая карточка резюме под заголовком «Мои резюме». */
    private AccessibilityNodeInfo findResumeCard(AccessibilityNodeInfo root) {
        int baseTop = -1;
        AccessibilityNodeInfo anchor = findByText(root, new String[]{"мои резюме"}, true);
        if (anchor != null) {
            Rect a = new Rect();
            anchor.getBoundsInScreen(a);
            baseTop = a.bottom;
        }
        for (AccessibilityNodeInfo n : flatten(root, 7000)) {
            String t = lower(n.getText());
            if (t.length() < 8) {
                continue;
            }
            if (t.equals("создать резюме") || t.startsWith("мои резюме")
                    || t.contains("₽") || t.contains("просмотр")
                    || t.contains("поднять")) {
                continue;
            }
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (b.width() <= 0 || b.height() < 50) {
                continue;
            }
            if (baseTop > 0 && b.top <= baseTop + 20) {
                continue;
            }
            if (b.top < screenHeight() * 0.55f) {
                continue;
            }
            return n;
        }
        return null;
    }

    private static String textOf(AccessibilityNodeInfo node) {
        if (node == null) {
            return "";
        }
        if (node.getText() != null) {
            return node.getText().toString();
        }
        if (node.getContentDescription() != null) {
            return node.getContentDescription().toString();
        }
        return "";
    }

    // ==================================================================
    // Цикл по вакансиям: описание -> Chatbox -> отклик
    // ==================================================================

    private void pickUpLoopTask() {
        SharedPreferences sp = prefs();
        boolean pending = sp.getBoolean(KEY_LOOP_PENDING, false);

        if (!pending) {
            if (loopActive) {
                BotLog.add("Цикл вакансий остановлен");
                loopActive = false;
                releaseLoopWakeLock();
            }
            return;
        }
        if (loopActive) {
            return;
        }
        // Даём отработать обычному поиску, если он запущен.
        if (task != null) {
            return;
        }

        long startedAt = sp.getLong(KEY_STARTED, 0);
        if (startedAt <= 0 || System.currentTimeMillis() - startedAt > STALE_TASK_MS) {
            sp.edit().putBoolean(KEY_LOOP_PENDING, false).apply();
            return;
        }

        loopActive = true;
        acquireLoopWakeLock();
        lpCount = Math.max(1, sp.getInt(KEY_LOOP_COUNT, 3));
        lpIndex = 0;
        lpBackAttempts = 0;
        lpDoneKeys.clear();
        lpSkipLogged.clear();
        Set<String> respondedKeys = sp.getStringSet(KEY_RESPONDED, null);
        if (respondedKeys != null) {
            // Вакансии из прошлых прогонов не открываем повторно.
            lpDoneKeys.addAll(respondedKeys);
        }
        lpDescription = "";
        lpAnswer = "";
        lpSentMessage = "";
        lpLetterInserted = false;
        lpDescRetries = 0;
        lpAnswerCandidate = "";
        lpScanLines.clear();
        lpScanSeen.clear();
        lpScanNoNew = 0;
        lpScanScrolls = 0;
        lpScanStop = false;
        lpLastCardBottom = 0;
        lpMoreTries = 0;
        lpMoreAt = 0;
        lpAdDetour = false;
        lpPendingCardKey = "";
        lpPendingCardAt = 0;
        lpAdExit = false;
        lpSkipNextBack = false;
        lpResent = false;
        lpNewChat = false;
        lpDsLaunched = false;
        lpShareSent = false;
        lpShareRetry = false;
        lpInsertCount = 0;
        lpInsertAt = 0;
        lpSendTries = 0;
        lpKeyboardHidden = false;
        lpSendAt = 0;
        lpNewChatTries = 0;
        lpCurrentKey = "";
        lpCurrentTitle = "";
        setLoopPhase(LP_WAIT_LIST);

        BotLog.add("▶ Цикл по вакансиям: " + lpCount + " шт.");
        try {
            Toast.makeText(this, "Цикл по вакансиям запущен: " + lpCount,
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private void setLoopPhase(int phase) {
        lpPhase = phase;
        lpPhaseSince = SystemClock.uptimeMillis();
        lpAttempts = 0;
        lpNextActionAt = 0;
    }

    private void pause(long ms) {
        lpNextActionAt = SystemClock.uptimeMillis() + ms;
    }

    /** Запоминает вакансию с откликом, чтобы не открывать её в новых прогонах. */
    private void markResponded(String key) {
        if (key == null || key.trim().isEmpty()) {
            return;
        }
        try {
            SharedPreferences sp = prefs();
            Set<String> old = sp.getStringSet(KEY_RESPONDED, null);
            Set<String> next = new HashSet<>();
            if (old != null) {
                next.addAll(old);
            }
            if (next.size() > 5000) {
                next.clear();
            }
            if (next.add(key.trim())) {
                sp.edit().putStringSet(KEY_RESPONDED, next).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    private void finishLoop(String message) {
        BotLog.add(message);
        prefs().edit().putBoolean(KEY_LOOP_PENDING, false).apply();
        loopActive = false;
        releaseLoopWakeLock();
        try {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Название карточки совпадает с вакансией, на которую уже откликнулись.
     * В списке и на странице вакансии названия могут отличаться хвостом
     * («Инженер-разработчик» и «Инженер-разработчик РЭБ»), поэтому кроме
     * точного совпадения проверяем начало строки.
     */
    private boolean isRespondedKey(String key) {
        if (key == null || key.length() < 12) {
            return false;
        }
        for (String done : lpDoneKeys) {
            if (done == null || done.length() < 12) {
                continue;
            }
            if (done.equals(key)) {
                return true;
            }
            if (Math.abs(done.length() - key.length()) > 25) {
                continue;
            }
            if (done.startsWith(key) || key.startsWith(done)) {
                if (lpSkipLogged.add(key)) {
                    BotLog.add("↷ Уже откликались — не открываю: " + shorten(key, 60));
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Пока идёт цикл, экран должен быть включён: у выключенного экрана нет
     * активного окна, и служба доступности не находит hh.ru.
     */
    @SuppressWarnings("deprecation")
    private void acquireLoopWakeLock() {
        try {
            if (lpWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                lpWakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_DIM_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "hhbot:loop");
                lpWakeLock.setReferenceCounted(false);
            }
            if (!lpWakeLock.isHeld()) {
                lpWakeLock.acquire(45 * 60 * 1000L);
                BotLog.add("Экран включён на время цикла");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Не удалось включить экран: " + t);
        }
    }

    private void releaseLoopWakeLock() {
        try {
            if (lpWakeLock != null && lpWakeLock.isHeld()) {
                lpWakeLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    private void loopStep() {
        try {
            loopStepInternal();
        } catch (Throwable t) {
            BotLog.add("Ошибка цикла: " + t);
        }
    }

    private void loopStepInternal() {
        if (!loopActive) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (lpNextActionAt > 0 && now < lpNextActionAt) {
            return;
        }
        lpNextActionAt = 0;
        lpAttempts++;

        switch (lpPhase) {
            case LP_WAIT_LIST: loopWaitList(); break;
            case LP_OPEN_VACANCY: loopOpenVacancy(); break;
            case LP_WAIT_VACANCY: loopWaitVacancy(); break;
            case LP_COPY_DESC: loopCopyDesc(); break;
            case LP_WAIT_DESC: loopWaitDesc(); break;
            case LP_SCAN_DESC: loopScanDesc(); break;
            case LP_DS_OPEN: loopChatboxOpen(); break;
            case LP_DS_SEND: loopChatboxSend(); break;
            case LP_DS_WAIT: loopChatboxWait(); break;
            case LP_COPY_ANSWER: loopCopyAnswer(); break;
            case LP_BACK_HH: loopBackHh(); break;
            case LP_RESPOND: loopRespond(); break;
            case LP_RESPOND_FILL: loopRespondFill(); break;
            case LP_RESPOND_SEND: loopRespondSend(); break;
            case LP_NEXT: loopNext(); break;
            default: break;
        }
    }

    // ---------------- шаги цикла ----------------

    private void loopWaitList() {
        String pkg = prefs().getString(KEY_PACKAGE, "ru.hh.android");
        AccessibilityNodeInfo root = findRootFor(pkg);

        if (dismissBlocker(root)) {
            return;
        }
        if (root != null && isVacancyList(root)) {
            BotLog.add("Список вакансий открыт — начинаю обход");
            setLoopPhase(LP_OPEN_VACANCY);
            return;
        }
        if (root != null && isVacancyPage(root)) {
            if (lpAttempts % 8 == 0 && lpBackAttempts < 8) {
                lpBackAttempts++;
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
            return;
        }
        if (lpAttempts == 40) {
            bringHhToFront();
        }
        if (lpAttempts > 140) {
            logScreen(pkg, 25);
            finishLoop("⚠ Не удалось открыть список вакансий hh.ru");
        }
    }

    private void loopOpenVacancy() {
        String pkg = prefs().getString(KEY_PACKAGE, "ru.hh.android");
        AccessibilityNodeInfo root = findRootFor(pkg);
        if (root == null) {
            if (lpAttempts % 8 == 0) {
                bringHhToFront();
            }
            if (lpAttempts > 80) {
                finishLoop("⚠ Окно hh.ru пропало во время обхода");
            }
            return;
        }
        if (isVacancyPage(root)) {
            setLoopPhase(LP_WAIT_VACANCY);
            return;
        }
        if (dismissBlocker(root)) {
            return;
        }

        Card card = findNextCard(root);
        if (card == null) {
            lpPendingCardKey = "";
            lpPendingCardAt = 0;
            long now = SystemClock.uptimeMillis();
            // Вакансии на странице закончились — hh.ru показывает кнопку
            // «Ещё вакансии». Нажимаем её и ждём, пока подгрузятся новые
            // карточки, вместо завершения цикла.
            if (lpMoreTries < 6 && now - lpMoreAt > 10000
                    && clickMoreVacancies(root)) {
                lpMoreTries++;
                lpMoreAt = now;
                BotLog.add("Нажимаю «Ещё вакансии» — жду подгрузку ("
                        + lpMoreTries + ")");
                pause(2500);
                return;
            }
            if (lpAttempts % 5 == 0 && lpAttempts > 0) {
                if (lpLastCardBottom > 0) {
                    // Следующая карточка ниже видимой области — листаем вниз.
                    swipe(360, 1100, 360, 620, 500);
                    lpLastCardBottom = 0;
                } else {
                    swipe(360, 1100, 360, 620, 500);
                }
            }
            if (lpAttempts > 140) {
                logScreen(pkg, 30);
                finishLoop("⚠ Не нашёл следующую вакансию в списке");
            }
            return;
        }
        lpMoreTries = 0;

        // hh.ru сначала показывает название карточки, а метки «Вы откликнулись»
        // и рекламные строки («промокод», «курс») дорисовываются чуть позже.
        // Даём карточке ~1 с, иначе бот успевает открыть рекламу или уже
        // пройденную вакансию.
        long cardNow = SystemClock.uptimeMillis();
        if (!card.key.equals(lpPendingCardKey)) {
            lpPendingCardKey = card.key;
            lpPendingCardAt = cardNow;
            return;
        }
        if (cardNow - lpPendingCardAt < 1000) {
            return;
        }
        lpPendingCardKey = "";

        lpCurrentKey = card.key;
        lpCurrentTitle = card.title;
        Rect cardBounds = new Rect();
        card.node.getBoundsInScreen(cardBounds);
        lpLastCardBottom = cardBounds.bottom;
        BotLog.add("→ Вакансия " + (lpIndex + 1) + "/" + lpCount + ": "
                + shorten(card.title, 70));

        boolean opened = false;
        try {
            opened = card.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Throwable ignored) {
        }
        if (!opened) {
            Rect b = new Rect();
            card.node.getBoundsInScreen(b);
            tap(b.exactCenterX(), b.top + Math.min(90, Math.max(30, b.height() / 5)));
        }
        setLoopPhase(LP_WAIT_VACANCY);
    }

    private void loopWaitVacancy() {
        // Даём человеку 2–3 секунды посмотреть на экран.
        if (lpAttempts < 10) {
            return;
        }
        String pkg = prefs().getString(KEY_PACKAGE, "ru.hh.android");
        AccessibilityNodeInfo root = findRootFor(pkg);
        if (lpAdExit) {
            loopAdExit(root);
            return;
        }
        if (root == null) {
            // Тап по рекламной карточке курсов уводит в браузер или на
            // постороннюю веб-страницу. Возвращаемся назад и пропускаем карточку.
            String top = topPackage();
            if (lpAttempts >= 6 && !top.isEmpty() && !pkg.equals(top)) {
                lpAdDetour = true;
                if (lpAttempts % 6 == 0 && lpBackAttempts < 3) {
                    lpBackAttempts++;
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    BotLog.add("Открылась посторонняя страница — возвращаюсь назад");
                    pause(1200);
                    return;
                }
                if (lpAttempts > 24) {
                    bringHhToFront();
                    if (lpAttempts > 40) {
                        lpDoneKeys.add(lpCurrentKey);
                        BotLog.add("⚠ Вместо вакансии открылась реклама — пропускаю: "
                                + shorten(lpCurrentTitle, 60));
                        setLoopPhase(LP_NEXT);
                    }
                }
            }
            return;
        }
        if (lpAdDetour && isVacancyList(root)) {
            // Вернулись к списку после рекламы — карточку больше не открываем.
            lpDoneKeys.add(lpCurrentKey);
            BotLog.add("↷ Реклама — пропускаю карточку: "
                    + shorten(lpCurrentTitle, 60));
            lpSkipNextBack = true;
            setLoopPhase(LP_NEXT);
            return;
        }
        if (isVacancyPage(root)) {
            if (hasRespondedOnPage(root)) {
                markResponded(lpCurrentKey);
                BotLog.add("↷ Уже есть отклик — пропускаю: "
                        + shorten(lpCurrentTitle, 60));
                setLoopPhase(LP_NEXT);
                return;
            }
            lpDoneKeys.add(lpCurrentKey);
            BotLog.add("Страница вакансии открыта");
            setLoopPhase(LP_COPY_DESC);
            return;
        }
        // Реклама курсов открылась внутри hh.ru (встроенный браузер).
        if (looksLikeAdPage(root)) {
            BotLog.add("⚠ Открылась реклама вместо вакансии — закрываю: "
                    + shorten(lpCurrentTitle, 60));
            lpAdDetour = true;
            lpAdExit = true;
            lpDoneKeys.add(lpCurrentKey);
            performGlobalAction(GLOBAL_ACTION_BACK);
            pause(1500);
            return;
        }
        // Иногда тап по карточке попадает во внутреннюю кнопку и открывает
        // шторку отклика — закрываем её и пробуем ещё раз.
        if (hasAnyText(root, new String[]{"отклик на вакансию",
                "добавить сопроводительное"})) {
            if (lpAttempts % 5 == 0) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                lpBackAttempts++;
                BotLog.add("Закрываю случайно открытую шторку отклика");
            }
            if (lpAttempts > 30) {
                BotLog.add("⚠ Страница вакансии не открылась, пропускаю");
                setLoopPhase(LP_NEXT);
            }
            return;
        }
        if (isVacancyList(root) && lpAttempts % 8 == 0 && lpBackAttempts < 3) {
            // Не открылось — возможно, попали по кнопке внутри карточки.
            lpBackAttempts++;
            performGlobalAction(GLOBAL_ACTION_BACK);
            return;
        }
        if (lpAttempts > 50) {
            BotLog.add("⚠ Страница вакансии не открылась, пропускаю");
            setLoopPhase(LP_NEXT);
        }
    }

    /**
     * Встроенный браузер hh.ru с рекламой курсов. «Назад» показывает окно
     * «Вы точно хотите выйти?» — подтверждаем и заново открываем hh.ru:
     * список вакансий восстанавливается на прежнем месте.
     */
    private void loopAdExit(AccessibilityNodeInfo root) {
        if (root == null) {
            if (lpAttempts == 1 || lpAttempts % 8 == 0) {
                bringHhToFront();
                pause(2500);
            }
            if (lpAttempts > 24) {
                finishAdExit();
            }
            return;
        }
        if (hasAnyText(root, new String[]{"вы точно хотите выйти"})) {
            AccessibilityNodeInfo yes = findByText(root,
                    new String[]{"да, выйти"}, false);
            if (yes != null) {
                click(yes);
            } else {
                tap(screenWidth() * 0.5f, screenHeight() * 0.89f);
            }
            BotLog.add("Закрываю окно выхода из рекламы");
            pause(2000);
            return;
        }
        if (isVacancyList(root)) {
            BotLog.add("Вернулся к списку вакансий");
            finishAdExit();
            return;
        }
        if (lpAttempts % 4 == 1) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            pause(1300);
            return;
        }
        if (lpAttempts > 28) {
            bringHhToFront();
        }
        if (lpAttempts > 44) {
            finishAdExit();
        }
    }

    private void finishAdExit() {
        lpAdExit = false;
        lpDoneKeys.add(lpCurrentKey);
        lpSkipNextBack = true;
        setLoopPhase(LP_NEXT);
    }

    private void loopCopyDesc() {
        // Раньше здесь было долгое нажатие и «Выбрать все / Копировать».
        // Долгое нажатие по описанию часто попадало в ссылку и открывало
        // карту или сайт компании, поэтому сразу читаем дерево доступности.
        BotLog.add("Собираю описание вакансии со страницы (без выделения текста)");
        startDescScan();
    }

    private void loopWaitDesc() {
        long at = prefs().getLong(KEY_CLIPBOARD_AT, 0);
        if (at > lpLastClipboardAt) {
            lpLastClipboardAt = at;
            String text = prefs().getString(KEY_CLIPBOARD, "");
            if (text != null && text.trim().length() > 120
                    && !text.contains("__hhbot_desc__")) {
                lpDescription = text.trim();
                BotLog.add("Описание скопировано: " + lpDescription.length() + " симв.");
                setLoopPhase(LP_DS_OPEN);
                return;
            }
            lpDescRetries++;
            BotLog.add("⚠ Описание не скопировалось (" + (text == null ? 0 : text.trim().length())
                    + " симв.) — собираю текст экрана");
            startDescScan();
            return;
        }
        if (lpAttempts == 12) {
            readClipboardViaRelay("desc");
        }
        if (lpAttempts > 30) {
            BotLog.add("⚠ Не удалось прочитать описание вакансии");
            startDescScan();
        }
    }

    /** Запасной путь: собираем текст страницы вакансии из дерева доступности. */
    private void startDescScan() {
        AccessibilityNodeInfo root = findRootFor(
                prefs().getString(KEY_PACKAGE, "ru.hh.android"));
        if (root != null && hasAnyText(root,
                new String[]{"выбрать все", "выделить все", "копировать"})) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            BotLog.add("Закрываю меню выделения текста");
        }
        lpScanLines.clear();
        lpScanSeen.clear();
        lpScanNoNew = 0;
        lpScanScrolls = 0;
        lpScanStop = false;
        setLoopPhase(LP_SCAN_DESC);
    }

    private void loopScanDesc() {
        String pkg = prefs().getString(KEY_PACKAGE, "ru.hh.android");
        AccessibilityNodeInfo root = findRootFor(pkg);
        if (root == null) {
            if (lpAttempts > 30) {
                BotLog.add("⚠ Окно hh.ru пропало при сборе описания");
                setLoopPhase(LP_NEXT);
            }
            return;
        }

        // Даём странице вакансии дорисоваться после открытия.
        if (lpAttempts < 8) {
            return;
        }

        int before = lpScanLines.size();
        collectVacancyLines(root);
        if (lpScanLines.size() > before) {
            lpScanNoNew = 0;
        } else {
            lpScanNoNew++;
        }

        String text = buildDescription();
        boolean finish = lpScanStop
                || lpScanScrolls >= 8
                || (lpScanNoNew >= 3 && text.length() >= 500)
                || lpScanNoNew >= 12;
        if (finish) {
            if (text.length() < 60) {
                BotLog.add("⚠ Описание вакансии не найдено на экране ("
                        + text.length() + " симв.) — пропускаю");
                setLoopPhase(LP_NEXT);
                return;
            }
            lpDescription = text;
            BotLog.add("Описание собрано с экрана: " + text.length() + " симв., "
                    + lpScanLines.size() + " строк");
            setLoopPhase(LP_DS_OPEN);
            return;
        }

        swipe(screenWidth() * 0.5f, screenHeight() * 0.75f,
                screenWidth() * 0.5f, screenHeight() * 0.30f, 550);
        lpScanScrolls++;
        pause(800);
    }

    private String buildDescription() {
        StringBuilder sb = new StringBuilder();
        for (String line : lpScanLines) {
            if (isSimilarVacanciesMark(lower(line))) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString().trim();
    }

    private void collectVacancyLines(AccessibilityNodeInfo root) {
        for (AccessibilityNodeInfo n : flatten(root, 7000)) {
            String text = n.getText() == null ? "" : n.getText().toString();
            if (text.trim().isEmpty() && n.getContentDescription() != null) {
                text = n.getContentDescription().toString();
            }
            if (text.trim().isEmpty()) {
                continue;
            }
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String low = lower(line);
                if (isVacancyChrome(low)) {
                    continue;
                }
                if (isSimilarVacanciesMark(low)) {
                    lpScanStop = true;
                    return;
                }
                if (lpScanSeen.add(low)) {
                    lpScanLines.add(line);
                }
            }
        }
    }

    private static boolean isSimilarVacanciesMark(String low) {
        return low.equals("похожие вакансии")
                || low.startsWith("похожие вакансии")
                || low.contains("вакансии по вашему запросу")
                || low.equals("вам может подойти")
                || low.startsWith("вам может подойти");
    }

    private static boolean isVacancyChrome(String low) {
        switch (low) {
            case "поиск":
            case "подработка":
            case "карьера":
            case "общение":
            case "профиль":
            case "назад":
            case "откликнуться":
            case "добавить сопроводительное":
            case "поделиться":
            case "пожаловаться":
            case "сохранить":
            case "написать":
            case "позвонить":
            case "выбрать все":
            case "выделить все":
            case "копировать":
            case "вырезать":
            case "вставить":
            case "все":
                return true;
            default:
                break;
        }
        if (low.matches("^[\\d\\s+]+$")) {
            return true;
        }
        if (low.contains("человек уже отклик") || low.contains("человека уже отклик")
                || low.contains("сейчас смотр") || low.contains("уже откликнул")) {
            return true;
        }
        return low.startsWith("опубликован");
    }

    private void loopChatboxOpen() {
        String dsPkg = prefs().getString(KEY_CHATBOX_PKG, DEFAULT_CHATBOX_PKG);
        if (lpSentMessage.isEmpty()) {
            String resume = prefs().getString(KEY_RESUME_TEXT, "");
            StringBuilder sb = new StringBuilder();
            sb.append(PROMPT_TEXT).append("\n\n");
            sb.append("Описание вакансии:\n").append(lpDescription);
            if (resume != null && !resume.trim().isEmpty()) {
                sb.append("\n\nРезюме:\n").append(resume.trim());
            } else {
                BotLog.add("⚠ Резюме не заполнено — письмо будет только по описанию вакансии");
            }
            sb.append("\n\n").append(PROMPT_FORMAT);
            lpSentMessage = sb.toString();
        }

        AccessibilityNodeInfo root = findRootFor(dsPkg);
        if (root == null) {
            if (!lpDsLaunched || lpAttempts % 10 == 0) {
                if (!startChatbox(dsPkg)) {
                    setLoopPhase(LP_NEXT);
                    return;
                }
                lpDsLaunched = true;
                BotLog.add("Открываю Chatbox");
                pause(3500);
                return;
            }
            if (lpAttempts > 60) {
                BotLog.add("⚠ Окно Chatbox не появилось");
                setLoopPhase(LP_NEXT);
            }
            return;
        }

        if (!lpNewChat) {
            // Новый чат — чтобы ответ не путался с прошлой вакансией.
            // В шапке открытого чата есть кнопка «Новый чат»; на пустом
            // стартовом экране её нет, и новый чат уже открыт.
            AccessibilityNodeInfo newChat = findByDesc(root, "новый чат");
            if (newChat == null) {
                newChat = findByText(root, new String[]{"новый чат"}, false);
            }
            if (newChat != null && lpNewChatTries < 3
                    && hasConversationText(root)) {
                // В чате ещё видны сообщения прошлой вакансии — открываем
                // новый и проверяем результат на следующем тике.
                lpNewChatTries++;
                Rect nb = new Rect();
                newChat.getBoundsInScreen(nb);
                // Кнопка в WebView: ACTION_CLICK не срабатывает, нужен жест.
                if (tap(nb.exactCenterX(), nb.exactCenterY())) {
                    BotLog.add("Chatbox: новый чат (" + lpNewChatTries + ")");
                    pause(1800);
                    return;
                }
            }
            lpNewChat = true;
        }

        // Chatbox не принимает текст через «Поделиться» — сразу вставляем
        // запрос в поле ввода (пустое поле нового чата).
        lpShareSent = true;
        BotLog.add("Chatbox открыт — вставляю запрос");
        setLoopPhase(LP_DS_SEND);
    }

    private boolean startChatbox(String dsPkg) {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(dsPkg);
            if (launch == null) {
                BotLog.add("⚠ Приложение Chatbox не найдено: " + dsPkg);
                return false;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            return true;
        } catch (Throwable t) {
            BotLog.add("⚠ Не удалось запустить Chatbox: " + t);
            return false;
        }
    }

    private void loopChatboxSend() {
        String dsPkg = prefs().getString(KEY_CHATBOX_PKG, DEFAULT_CHATBOX_PKG);
        if (lpAttempts < 4) {
            return;
        }
        AccessibilityNodeInfo field = findChatboxInput(dsPkg);
        AccessibilityNodeInfo root = findRootFor(dsPkg);
        if (field == null) {
            // Поле ввода свёрнуто — нажимаем на строку «Напишите…».
            if (lpAttempts % 4 == 0) {
                if (focusChatboxInput(dsPkg)) {
                    pause(900);
                }
            }
            if (lpAttempts > 60) {
                logScreen(dsPkg, 25);
                BotLog.add("⚠ Не нашёл поле ввода Chatbox");
                setLoopPhase(LP_NEXT);
            }
            return;
        }

        if (nodeTextLength(field) < 80
                && !hasAnyText(root, new String[]{"сформулируй сопроводительное"})) {
            // Текст ещё не появился в поле: «Поделиться» подгружается
            // или не сработало — вставляем сами, но не чаще раза в 3 секунды,
            // иначе поле заполняется одним и тем же запросом много раз.
            long now = SystemClock.uptimeMillis();
            if (lpInsertCount < 3 && now - lpInsertAt > 3000) {
                lpInsertCount++;
                lpInsertAt = now;
                lpShareRetry = true;
                boolean inserted = insertChatboxPrompt(dsPkg);
                BotLog.add((inserted ? "Вставляю запрос в поле Chatbox (попытка "
                        : "⚠ Не удалось вставить запрос в поле Chatbox (попытка ")
                        + lpInsertCount + ")");
                pause(1500);
                return;
            }
            if (lpInsertCount >= 3 && now - lpInsertAt > 15000) {
                logScreen(dsPkg, 25);
                BotLog.add("⚠ Поле ввода Chatbox осталось пустым — пропускаю вакансию");
                setLoopPhase(LP_NEXT);
            }
            return;
        }

        // Клавиатура сдвигает строку ввода вверх, из-за чего координаты
        // кнопки «Отправить» в дереве устаревают. Прячем её, ТОЛЬКО если
        // она реально показана: «Назад» на пустой клавиатуре закрывает
        // сам чат Chatbox и выглядит как «открылся новый чат».
        if (!lpKeyboardHidden) {
            lpKeyboardHidden = true;
            if (isImeVisible()) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                pause(1200);
                return;
            }
        }

        long sendNow = SystemClock.uptimeMillis();
        if (sendNow - lpSendAt < 1800) {
            return;
        }
        lpSendAt = sendNow;

        if (tapChatboxSend()) {
            lpSendTries++;
            BotLog.add("Нажимаю «Отправить» в Chatbox (" + lpSendTries + ")");
            lpSentAt = SystemClock.uptimeMillis();
            lpSawGenerating = false;
            lpAnswer = "";
            lpAnswerCandidate = "";
            lpAnswerStableSince = 0;
            lpAnswerHadHeader = false;
            setLoopPhase(LP_DS_WAIT);
            return;
        }
        lpSendTries++;
        if (lpSendTries == 8) {
            lpSendAt = 0;
            if (isImeVisible()) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                BotLog.add("Клавиатура закрыта — ищу кнопку отправки ещё раз");
            }
            pause(900);
            return;
        }
        if (lpSendTries > 24) {
            logScreen(dsPkg, 25);
            BotLog.add("⚠ Не нашёл кнопку отправки в Chatbox");
            setLoopPhase(LP_NEXT);
        }
    }

    /** Chatbox генерирует ответ: кнопка «Отправить» заменена на «Остановить». */
    private boolean isChatboxGenerating(AccessibilityNodeInfo root) {
        return root != null && hasAnyText(root, new String[]{
                "остановить", "дождитесь завершения"});
    }

    private static int nodeTextLength(AccessibilityNodeInfo node) {
        if (node == null) {
            return 0;
        }
        try {
            CharSequence cs = node.getText();
            return cs == null ? 0 : cs.length();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Самое нижнее поле ввода на экране Chatbox — это строка сообщения. */
    private AccessibilityNodeInfo findChatboxInput(String dsPkg) {
        AccessibilityNodeInfo root = findRootFor(dsPkg);
        if (root == null) {
            return null;
        }
        AccessibilityNodeInfo best = null;
        int bestTop = -1;
        for (AccessibilityNodeInfo n : flatten(root, 7000)) {
            if (!isEditField(n) || !isVisible(n)) {
                continue;
            }
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (b.width() < 200 || b.height() <= 0) {
                continue;
            }
            if (b.top > bestTop) {
                bestTop = b.top;
                best = n;
            }
        }
        return best;
    }

    private boolean focusChatboxInput(String dsPkg) {
        AccessibilityNodeInfo field = findChatboxInput(dsPkg);
        if (field != null) {
            try {
                if (field.isFocused()) {
                    return true;
                }
                if (field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            return click(field);
        }
        // Поле свёрнуто в строку «Напишите или удерживайте, чтобы говорить».
        AccessibilityNodeInfo root = findRootFor(dsPkg);
        if (root == null) {
            return false;
        }
        for (AccessibilityNodeInfo n : flatten(root, 7000)) {
            if (!isVisible(n) || isEditField(n)) {
                continue;
            }
            String t = lower(n.getText());
            String d = lower(n.getContentDescription());
            if (t.startsWith("напишите") || d.startsWith("напишите")
                    || t.startsWith("введите ваш вопрос") || d.startsWith("введите ваш вопрос")) {
                return click(n);
            }
        }
        return false;
    }

    private boolean insertChatboxPrompt(String dsPkg) {
        AccessibilityNodeInfo field = findChatboxInput(dsPkg);
        if (field == null) {
            return false;
        }
        try {
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    lpSentMessage);
            if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        setClipboard(lpSentMessage);
        try {
            if (field.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        Rect b = new Rect();
        field.getBoundsInScreen(b);
        longPress(b.exactCenterX(), b.exactCenterY());
        pause(700);
        return clickAnyText(new String[]{"вставить"});
    }

    private void loopChatboxWait() {
        long now = SystemClock.uptimeMillis();
        String dsPkg = prefs().getString(KEY_CHATBOX_PKG, DEFAULT_CHATBOX_PKG);
        AccessibilityNodeInfo root = findRootFor(dsPkg);
        if (root == null) {
            if (lpAttempts > 400) {
                BotLog.add("⚠ Окно Chatbox пропало");
                setLoopPhase(LP_NEXT);
            }
            return;
        }

        boolean generating = isChatboxGenerating(root);
        if (generating) {
            lpSawGenerating = true;
        }

        // Клик по «Отправить» иногда не срабатывает (координаты кнопки
        // успевают устареть): текст остался в поле ввода, ответ не начался.
        AccessibilityNodeInfo composer = findChatboxInput(dsPkg);
        boolean composerHasPrompt = composer != null
                && nodeTextLength(composer) > 80;
        if (!generating && composerHasPrompt && now - lpSentAt > 6000) {
            if (lpSendTries < 8) {
                BotLog.add("⚠ Chatbox не принял запрос — отправляю ещё раз");
                lpKeyboardHidden = false;
                setLoopPhase(LP_DS_SEND);
            } else {
                BotLog.add("⚠ Не удалось отправить запрос в Chatbox");
                setLoopPhase(LP_NEXT);
            }
            return;
        }

        // Chatbox может потерять открытый чат (например, после перезапуска
        // приложения) и показать приветствие. Тогда отправляем запрос заново.
        // Важно: текст запроса может быть просто прокручен вверх, поэтому
        // проверяем, есть ли в чате хоть какой-то длинный текст.
        if (now - lpSentAt > 30000
                && !generating
                && !hasConversationText(root)
                && !hasAnyText(root, new String[]{"сформулируй сопроводительное"})) {
            if (!lpResent) {
                lpResent = true;
                lpNewChat = false;
                lpShareSent = false;
                lpShareRetry = false;
                lpInsertCount = 0;
                lpInsertAt = 0;
                lpSendTries = 0;
                BotLog.add("⚠ Chatbox потерял чат — отправляю запрос заново");
                setLoopPhase(LP_DS_OPEN);
            } else {
                BotLog.add("⚠ Chatbox не показывает отправленный запрос");
                setLoopPhase(LP_NEXT);
            }
            return;
        }

        // Ждём, пока модель закончит рассуждения и напишет письмо.
        // Готовым считаем только текст, который начинается с «Тема:»
        // и уже не меняется несколько секунд.
        if (lpAttempts % 2 == 0) {
            String answer = extractChatboxAnswer(root);
            if (answer != null && answer.length() > 40) {
                boolean withTheme = themeIndex(answer) >= 0;
                boolean withHeader = withTheme || lpAnswerHadHeader;
                if (!answer.equals(lpAnswerCandidate)) {
                    lpAnswerCandidate = answer;
                    lpAnswerStableSince = now;
                } else {
                    long stable = now - lpAnswerStableSince;
                    boolean ready;
                    if (withHeader) {
                        long needStable = lpSawGenerating ? 2500 : 5000;
                        ready = !generating && stable > needStable;
                    } else {
                        // Заголовка нет — берём письмо, только если оно уже
                        // не меняется и похоже на готовый текст.
                        ready = !generating && stable > 6000
                                && now - lpSentAt > 25000
                                && looksLikeLetter(answer);
                    }
                    if (ready) {
                        String letter = letterFromTheme(answer);
                        lpAnswer = letter;
                        BotLog.add((withTheme
                                ? "Chatbox закончил письмо — беру текст после «Тема:»: "
                                : "Chatbox закончил письмо: ")
                                + letter.length() + " симв.");
                        setLoopPhase(LP_BACK_HH);
                        return;
                    }
                }
            }
        }

        if (now - lpSentAt > 300000) {
            BotLog.add("⚠ Таймаут ожидания ответа Chatbox");
            setLoopPhase(LP_COPY_ANSWER);
        }
    }

    /** Запасной путь: пробуем прочитать готовый ответ прямо из дерева. */
    private void loopCopyAnswer() {
        String dsPkg = prefs().getString(KEY_CHATBOX_PKG, DEFAULT_CHATBOX_PKG);
        AccessibilityNodeInfo root = findRootFor(dsPkg);
        String answer = extractChatboxAnswer(root);
        if (answer != null && answer.length() > 60) {
            lpAnswer = letterFromTheme(answer);
            BotLog.add("Ответ Chatbox прочитан из интерфейса: "
                    + lpAnswer.length() + " симв.");
            setLoopPhase(LP_BACK_HH);
            return;
        }
        if (lpAttempts > 40) {
            logScreen(dsPkg, 30);
            BotLog.add("⚠ Не удалось прочитать ответ Chatbox");
            setLoopPhase(LP_NEXT);
        }
    }

    private static boolean looksLikeLetter(String text) {
        String low = text.toLowerCase(Locale.ROOT);
        return low.contains("уважаем") || low.contains("здравствуйте")
                || low.contains("добрый день") || low.contains("меня зовут")
                || low.contains("рассмотрите мо") || low.contains("хочу откликнуться");
    }

    private void loopBackHh() {
        if (lpAttempts == 1) {
            bringHhToFront();
            pause(1600);
            return;
        }
        String pkg = prefs().getString(KEY_PACKAGE, "ru.hh.android");
        AccessibilityNodeInfo root = findRootFor(pkg);
        if (root != null && isVacancyPage(root)) {
            setLoopPhase(LP_RESPOND);
            return;
        }
        if (lpAttempts == 20) {
            bringHhToFront();
        }
        if (lpAttempts > 60) {
            logScreen(pkg, 25);
            BotLog.add("⚠ Не удалось вернуться на страницу вакансии");
            setLoopPhase(LP_NEXT);
        }
    }

    private void loopRespond() {
        String pkg = prefs().getString(KEY_PACKAGE, "ru.hh.android");
        AccessibilityNodeInfo root = findRootFor(pkg);
        if (root == null) {
            if (lpAttempts > 30) {
                setLoopPhase(LP_NEXT);
            }
            return;
        }
        if (dismissBlocker(root)) {
            return;
        }
        if (lpAnswer.trim().length() < 20) {
            BotLog.add("⚠ Нет письма от Chatbox — отклик не отправляю");
            setLoopPhase(LP_NEXT);
            return;
        }
        if (hasRespondedOnPage(root)) {
            markResponded(lpCurrentKey);
            BotLog.add("↷ Отклик уже есть — пропускаю: "
                    + shorten(lpCurrentTitle, 60));
            setLoopPhase(LP_NEXT);
            return;
        }
        // Шторка отклика могла остаться открытой после возврата из Chatbox.
        if (findByText(root, new String[]{"добавить сопроводительное"}, false) != null) {
            BotLog.add("Шторка отклика уже открыта — заполняю письмо");
            setLoopPhase(LP_RESPOND_FILL);
            return;
        }

        AccessibilityNodeInfo button = findRespondButton(root);
        if (button != null) {
            Rect b = new Rect();
            button.getBoundsInScreen(b);
            // Кнопка «Откликнуться» на странице вакансии — внизу экрана.
            if (b.exactCenterY() > screenHeight() * 0.70f) {
                click(button);
                BotLog.add("Нажимаю «Откликнуться»");
                pause(1800);
                setLoopPhase(LP_RESPOND_FILL);
                return;
            }
        }
        if (lpAttempts % 8 == 0) {
            // Иногда кнопка появляется после небольшой прокрутки страницы.
            swipe(360, 1050, 360, 850, 300);
        }
        if (lpAttempts > 40) {
            logScreen(pkg, 30);
            BotLog.add("⚠ Кнопка «Откликнуться» не найдена");
            setLoopPhase(LP_NEXT);
        }
    }

    /**
     * На странице вакансии уже стоит «Вы откликнулись» / «Резюме отправлено».
     * Важно: hh.ru показывает этот маркер в верхней части страницы (под
     * заголовком), поэтому проверяем весь экран, а не только нижнюю половину.
     */
    private boolean hasRespondedOnPage(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        for (AccessibilityNodeInfo n : flatten(root, 7000)) {
            if (!isVisible(n)) {
                continue;
            }
            String t = lower(n.getText());
            String d = lower(n.getContentDescription());
            boolean hit = t.contains("вы откликнулись")
                    || t.contains("отклик отправлен")
                    || t.contains("резюме отправлено")
                    || t.contains("вам отказали")
                    || t.contains("вас пригласили")
                    || d.contains("вы откликнулись")
                    || d.contains("отклик отправлен")
                    || d.contains("резюме отправлено")
                    || d.contains("вам отказали")
                    || d.contains("вас пригласили");
            if (!hit) {
                continue;
            }
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (b.width() > 0 && b.height() > 0) {
                return true;
            }
        }
        return false;
    }


    private void loopRespondFill() {        String pkg = prefs().getString(KEY_PACKAGE, "ru.hh.android");
        AccessibilityNodeInfo root = findRootFor(pkg);
        if (root == null) {
            if (lpAttempts > 30) {
                setLoopPhase(LP_NEXT);
            }
            return;
        }

        AccessibilityNodeInfo field = findCoverLetterField(root);
        if (field == null) {
            // Сначала нужно раскрыть поле: «Добавить сопроводительное».
            AccessibilityNodeInfo addLetter = findByText(root,
                    new String[]{"добавить сопроводительное", "добавить письмо"}, false);
            if (addLetter != null) {
                if (click(addLetter)) {
                    BotLog.add("Открываю поле сопроводительного письма");
                }
                pause(1200);
                return;
            }
            if (lpAttempts == 10) {
                BotLog.add("Жду появления поля сопроводительного письма...");
            }
            if (lpAttempts > 40) {
                logScreen(pkg, 35);
                BotLog.add("⚠ Поле сопроводительного письма не найдено");
                setLoopPhase(LP_NEXT);
            }
            return;
        }

        setClipboard(lpAnswer);
        boolean ok = setNodeText(field, lpAnswer);
        if (!ok) {
            try {
                ok = field.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            } catch (Throwable ignored) {
            }
        }
        if (!ok) {
            Rect b = new Rect();
            field.getBoundsInScreen(b);
            tap(b.exactCenterX(), b.exactCenterY());
            try {
                ok = field.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            } catch (Throwable ignored) {
            }
        }
        BotLog.add(ok
                ? "Письмо вставлено в отклик (" + lpAnswer.length() + " симв.)"
                : "⚠ Не удалось вставить письмо автоматически");
        lpLetterInserted = ok;
        pause(1200);
        setLoopPhase(LP_RESPOND_SEND);
    }

    private void loopRespondSend() {
        if (lpAttempts < 4) {
            return;
        }
        if (!lpLetterInserted) {
            BotLog.add("⚠ Письмо не вставлено — отклик не отправляю");
            setLoopPhase(LP_NEXT);
            return;
        }
        String pkg = prefs().getString(KEY_PACKAGE, "ru.hh.android");
        AccessibilityNodeInfo root = findRootFor(pkg);
        if (root == null) {
            return;
        }

        AccessibilityNodeInfo button = findSendResponseButton(root);
        if (button != null) {
            click(button);
            BotLog.add("✅ Отклик отправлен: " + shorten(lpCurrentTitle, 70));
            markResponded(lpCurrentKey);
            pause(2000);
            setLoopPhase(LP_NEXT);
            return;
        }
        if (lpAttempts > 40) {
            logScreen(pkg, 35);
            BotLog.add("⚠ Кнопка отправки отклика не найдена");
            setLoopPhase(LP_NEXT);
        }
    }

    private void loopNext() {
        if (lpAttempts == 1) {
            if (!lpCurrentKey.isEmpty()) {
                lpDoneKeys.add(lpCurrentKey);
            }
            lpIndex++;
            prefs().edit().putInt(KEY_LOOP_INDEX, lpIndex).apply();
            lpCurrentKey = "";
            lpCurrentTitle = "";
            lpDescription = "";
            lpAnswer = "";
            lpSentMessage = "";
            lpLetterInserted = false;
            lpDescRetries = 0;
            lpAnswerCandidate = "";
            lpScanLines.clear();
            lpScanSeen.clear();
            lpScanNoNew = 0;
            lpScanScrolls = 0;
            lpScanStop = false;
            lpResent = false;
            lpNewChat = false;
            lpDsLaunched = false;
            lpShareSent = false;
            lpShareRetry = false;
            lpInsertCount = 0;
            lpInsertAt = 0;
            lpSendTries = 0;
            lpBackAttempts = 0;
            lpMoreTries = 0;
            lpMoreAt = 0;
            lpAdDetour = false;
            lpPendingCardKey = "";
            lpPendingCardAt = 0;
            lpAdExit = false;
            lpSkipNextBack = false;

            if (lpIndex >= lpCount) {
                finishLoop("🏁 Цикл завершён: обработано " + lpIndex + " вакансий");
                return;
            }
            BotLog.add("Перехожу к вакансии " + (lpIndex + 1) + " из " + lpCount);
            if (lpSkipNextBack) {
                // Реклама уже закрыта, и мы снова в списке — лишний «Назад»
                // выбросил бы из hh.ru на главный экран.
                lpSkipNextBack = false;
                pause(800);
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK);
                pause(1500);
            }
            return;
        }

        String pkg = prefs().getString(KEY_PACKAGE, "ru.hh.android");
        AccessibilityNodeInfo root = findRootFor(pkg);
        if (dismissBlocker(root)) {
            return;
        }
        if (root != null && isVacancyList(root)) {
            BotLog.add("Вернулся к списку вакансий");
            setLoopPhase(LP_OPEN_VACANCY);
            return;
        }
        // Ещё не в списке (шторка отклика, страница вакансии) — выходим назад.
        if (lpAttempts % 5 == 0 && lpBackAttempts < 8) {
            lpBackAttempts++;
            performGlobalAction(GLOBAL_ACTION_BACK);
            pause(1200);
            return;
        }
        if (lpAttempts == 60) {
            bringHhToFront();
        }
        if (lpAttempts > 120) {
            logScreen(pkg, 25);
            setLoopPhase(LP_OPEN_VACANCY);
        }
    }

    /**
     * hh.ru иногда показывает поверх списка модальные окна («Какой формат
     * удобнее?», «Укажите место» и т.п.). Закрываем их кнопкой «Назад».
     */
    private boolean dismissBlocker(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        if (!hasAnyText(root, new String[]{
                "какой формат удобнее", "сохранить и продолжить",
                "укажите место", "резюме доставлено",
                "вы посмотрели вакансию", "спасибо за ответ"})) {
            return false;
        }
        if (lpAttempts % 3 != 1) {
            return true;
        }
        performGlobalAction(GLOBAL_ACTION_BACK);
        BotLog.add("Закрываю всплывающее окно hh.ru");
        pause(900);
        return true;
    }

    // ---------------- поиск карточек и кнопок ----------------

    private static final class Card {
        AccessibilityNodeInfo node;
        String key;
        String title;
    }

    private Card findNextCard(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = flatten(root, 7000);
        int h = screenHeight();

        Card best = null;
        int bestTop = Integer.MAX_VALUE;
        Card below = null;
        int belowTop = Integer.MAX_VALUE;

        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isClickable() || !isVisible(node)) {
                continue;
            }
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            if (b.width() < 420) {
                continue;
            }
            if (b.height() < 180 || b.height() > 1400) {
                continue;
            }
            if (b.top < h * 0.30f || b.top > h * 0.90f) {
                continue;
            }
            if (b.exactCenterY() > h * 0.95f) {
                continue;
            }

            String title = firstLongText(node, 0);
            String plainTitle = lower(title);
            if (plainTitle.contains("ещё ваканси") || plainTitle.contains("показать ещё")
                    || plainTitle.contains("загрузить ещё")
                    || plainTitle.contains("ещё результаты")) {
                // Кнопка подгрузки списка — не вакансия.
                continue;
            }
            String key = title.length() >= 8
                    ? lower(title) : ("bounds:" + b.left + "," + b.top + ","
                    + b.right + "," + b.bottom);
            if (isAdCard(node)) {
                // Рекламные блоки (курсы «Нетология» и т.п.) — не вакансии.
                if (lpDoneKeys.add(key)) {
                    BotLog.add("↷ Реклама — пропускаю: "
                            + shorten(title.isEmpty() ? key : title, 60));
                }
                continue;
            }
            if (!looksLikeVacancyCard(node)) {
                // Карточка ещё не дорисовалась или это промо без признаков
                // вакансии. Не помечаем пройденной — проверим на следующем тике.
                continue;
            }
            if (lpDoneKeys.contains(key) || isRespondedKey(key)) {
                continue;
            }
            if (hasAnyText(node, new String[]{"вы откликнулись", "отклик отправлен",
                    "резюме отправлено", "вам отказали", "вас пригласили"})) {
                lpDoneKeys.add(key);
                continue;
            }
            if (b.top < bestTop) {
                bestTop = b.top;
                Card card = new Card();
                card.node = node;
                card.key = key;
                card.title = title.isEmpty() ? key : title;
                best = card;
            }
            // Карточка строго под последней открытой — приоритет для
            // последовательного обхода списка.
            if (lpLastCardBottom > 0 && b.top >= lpLastCardBottom - 40
                    && b.top < belowTop) {
                belowTop = b.top;
                Card card = new Card();
                card.node = node;
                card.key = key;
                card.title = title.isEmpty() ? key : title;
                below = card;
            }
        }
        if (lpLastCardBottom > 0) {
            // Если под последней карточкой ничего нет — нужно листать список,
            // а не прыгать на случайную карточку выше.
            return below;
        }
        return best;
    }

    /**
     * Рекламная карточка (курсы, промо): её текст не похож на вакансию.
     * Смотрим всё поддерево карточки — заголовок, метку «Реклама» и т.п.
     */
    private static boolean isAdCard(AccessibilityNodeInfo node) {
        String blob = lower(subtreeTextLimited(node, 1600));
        if (blob.isEmpty()) {
            return false;
        }
        if (blob.contains("реклама") || blob.contains("промокод")
                || blob.contains("вебинар") || blob.contains("буткемп")
                || blob.contains("скидк") || blob.contains("бесплатн")) {
            return true;
        }
        boolean vacancySigns = blob.contains("₽")
                || blob.contains("руб")
                || blob.contains("отклик")
                || blob.contains("опыт")
                || blob.contains("занятост")
                || blob.contains("ваканси");
        if (vacancySigns) {
            // Настоящая вакансия может упоминать курсы или обучение.
            return false;
        }
        return blob.contains("нетология")
                || blob.contains("курсы")
                || blob.contains("курс ")
                || blob.contains("онлайн-курс")
                || blob.contains("школ");
    }

    /**
     * Карточка похожа на настоящую вакансию. Рекламные карточки курсов
     * содержат цену и длительность, но у них нет опыта, даты публикации,
     * компании и кнопки отклика — по этим признакам и отличаем.
     */
    private static boolean looksLikeVacancyCard(AccessibilityNodeInfo node) {
        String blob = lower(subtreeTextLimited(node, 2500));
        return blob.contains("опыт")
                || blob.contains("опубликован")
                || blob.contains("отклик")
                || blob.contains("ваканси")
                || blob.contains("резюме")
                || blob.contains("выплат")
                || blob.contains("пригласили")
                || blob.contains("отказали")
                || blob.contains("работодател")
                || blob.contains("компани")
                || blob.contains("за месяц")
                || blob.contains("₽ за");
    }

    /** Реклама курсов, открытая вместо вакансии (внутри hh.ru или в браузере). */
    private boolean looksLikeAdPage(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        if (hasAnyText(root, new String[]{"откликнуться", "обязанности",
                "требования", "описание вакансии"})) {
            return false;
        }
        // Встроенный браузер: адресная строка и кнопка «Обновить».
        boolean browser = hasAnyText(root, new String[]{"обновить",
                "https://", "http://"});
        if (!browser) {
            return false;
        }
        return hasAnyText(root, new String[]{"реклама", "нетология",
                "промокод", "вебинар", "курсы", "скидк"});
    }

    /** Весь текст поддерева, не длиннее maxLen символов. */
    private static String subtreeTextLimited(AccessibilityNodeInfo node, int maxLen) {
        StringBuilder sb = new StringBuilder();
        appendSubtreeText(node, sb, 0, maxLen);
        return sb.toString();
    }

    private static void appendSubtreeText(AccessibilityNodeInfo node, StringBuilder sb,
                                          int depth, int maxLen) {
        if (node == null || depth > 8 || sb.length() >= maxLen) {
            return;
        }
        CharSequence text = node.getText();
        if (text != null) {
            sb.append(' ').append(text);
        }
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            sb.append(' ').append(desc);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count && sb.length() < maxLen; i++) {
            try {
                appendSubtreeText(node.getChild(i), sb, depth + 1, maxLen);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Кнопка «Ещё вакансии» / «Показать ещё» в конце списка. */
    private boolean clickMoreVacancies(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        for (AccessibilityNodeInfo n : flatten(root, 7000)) {
            if (!isVisible(n)) {
                continue;
            }
            String label = (lower(n.getText()) + " "
                    + lower(n.getContentDescription())).trim();
            if (label.isEmpty()) {
                continue;
            }
            if (label.contains("ваканси")
                    && (label.contains("ещё") || label.contains("еще")
                    || label.contains("показать") || label.contains("загрузить")
                    || label.contains("больше"))) {
                if (click(n)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String firstLongText(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 6) {
            return "";
        }
        CharSequence own = node.getText();
        if (own != null) {
            String t = own.toString().trim();
            if (t.length() >= 8 && !isCardNoise(t)) {
                return t;
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            try {
                String t = firstLongText(node.getChild(i), depth + 1);
                if (t.length() >= 8) {
                    return t;
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    /** Служебные подписи карточки, не являющиеся названием вакансии. */
    private static boolean isCardNoise(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        return t.startsWith("сейчас смотр")
                || t.startsWith("сейчас ")
                || t.contains("человек уже отклик")
                || t.contains("человека уже отклик")
                || t.contains("уже откликнул")
                || t.contains("смотрят")
                || t.contains("просмотр")
                || t.startsWith("опубликован")
                || t.startsWith("москва")
                || t.startsWith("можно удалённо");
    }

    private boolean isVacancyList(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        boolean results = false;
        for (AccessibilityNodeInfo n : flatten(root, 7000)) {
            String t = lower(n.getText());
            String d = lower(n.getContentDescription());
            if (t.contains("результаты поиска") || d.contains("результаты поиска")) {
                results = true;
            }
            // «20 177 вакансий», «899 334 вакансии»
            if (t.contains("ваканс") && t.matches(".*\\d.*")) {
                results = true;
            }
        }
        return results;
    }

    private boolean isVacancyPage(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        if (isVacancyList(root)) {
            return false;
        }
        int h = screenHeight();
        int w = getResources().getDisplayMetrics().widthPixels;
        for (AccessibilityNodeInfo n : flatten(root, 7000)) {
            String t = lower(n.getText());
            String d = lower(n.getContentDescription());
            if (t.contains("откликнуться") || d.contains("откликнуться")) {
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                // У страницы вакансии кнопка во всю ширину и по центру экрана.
                float cx = b.exactCenterX();
                if (b.exactCenterY() > h * 0.70f
                        && cx > w * 0.35f && cx < w * 0.65f) {
                    return true;
                }
            }
        }
        return hasAnyText(root, new String[]{
                "обязанности", "требования", "описание вакансии", "о вакансии",
                // Экран после отклика остаётся страницей вакансии.
                "резюме отправлено", "вы откликнулись", "отклик отправлен",
                "добавить сопроводительное"
        });
    }

    private AccessibilityNodeInfo findCoverLetterField(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = flatten(root, 7000);
        AccessibilityNodeInfo best = null;
        long bestScore = -1;

        for (AccessibilityNodeInfo n : nodes) {
            if (!isEditField(n) || !isVisible(n)) {
                continue;
            }
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            String text = allText(n);
            long score = (long) b.width() * b.height();
            if (text.contains("сопровод") || text.contains("письм")
                    || text.contains("сообщ") || text.contains("коммент")) {
                score += 10_000_000L;
            }
            if (b.height() > 150) {
                score += 2_000_000L;
            }
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    /**
     * Кнопка «Откликнуться» на странице вакансии. Берём самую нижнюю из
     * видимых: внизу страницы бывают карточки похожих вакансий со своей
     * кнопкой, а нужная кнопка закреплена в нижней панели.
     */
    private AccessibilityNodeInfo findRespondButton(AccessibilityNodeInfo root) {
        if (root == null) {
            return null;
        }
        int h = screenHeight();
        AccessibilityNodeInfo best = null;
        int bestY = -1;
        for (AccessibilityNodeInfo n : flatten(root, 7000)) {
            if (!isVisible(n)) {
                continue;
            }
            String t = lower(n.getText());
            String d = lower(n.getContentDescription());
            if (!(t.equals("откликнуться") || d.equals("откликнуться"))) {
                continue;
            }
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (b.width() < 100 || b.exactCenterY() < h * 0.70f) {
                continue;
            }
            int cy = Math.round(b.exactCenterY());
            if (cy > bestY) {
                bestY = cy;
                best = n;
            }
        }
        return best;
    }

    private AccessibilityNodeInfo findSendResponseButton(AccessibilityNodeInfo root) {        List<AccessibilityNodeInfo> nodes = flatten(root, 7000);
        AccessibilityNodeInfo best = null;
        int bestScore = 0;

        for (AccessibilityNodeInfo n : nodes) {
            if (!isVisible(n)) {
                continue;
            }
            String t = lower(n.getText());
            String d = lower(n.getContentDescription());
            int score = 0;
            if (t.equals("отправить") || d.equals("отправить")
                    || t.equals("отправить отклик") || d.equals("отправить отклик")
                    || t.equals("откликнуться") || d.equals("откликнуться")) {
                score = 120;
            } else if (t.contains("отправить") || d.contains("отправить")) {
                score = 90;
            }
            if (score == 0) {
                continue;
            }
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (b.exactCenterY() > screenHeight() * 0.55f) {
                score += 20;
            }
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    private boolean hasAnyText(AccessibilityNodeInfo root, String[] words) {
        if (root == null) {
            return false;
        }
        for (AccessibilityNodeInfo n : flatten(root, 7000)) {
            String t = lower(n.getText());
            String d = lower(n.getContentDescription());
            for (String w : words) {
                if (t.contains(w) || d.contains(w)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Ищет узел по content-desc (text не учитывается). */
    private AccessibilityNodeInfo findByDesc(AccessibilityNodeInfo root, String label) {
        if (root == null) {
            return null;
        }
        for (AccessibilityNodeInfo n : flatten(root, 7000)) {
            if (!isVisible(n)) {
                continue;
            }
            String d = lower(n.getContentDescription());
            if (d.equals(label)) {
                return n;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findByText(AccessibilityNodeInfo root,
                                             String[] words, boolean exact) {        if (root == null) {
            return null;
        }
        for (AccessibilityNodeInfo n : flatten(root, 7000)) {
            if (!isVisible(n)) {
                continue;
            }
            String t = lower(n.getText());
            String d = lower(n.getContentDescription());
            for (String w : words) {
                boolean match = exact ? (t.equals(w) || d.equals(w))
                        : (t.contains(w) || d.contains(w));
                if (match) {
                    return n;
                }
            }
        }
        return null;
    }

    /** Ищет текст во всех окнах (включая системный тулбар выделения). */
    private boolean clickAnyText(String[] words) {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) {
                    continue;
                }
                AccessibilityNodeInfo n = findByText(root, words, false);
                if (n != null && click(n)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ---------------- Chatbox ----------------

    private int chatboxComposerRowY() {
        String dsPkg = prefs().getString(KEY_CHATBOX_PKG, DEFAULT_CHATBOX_PKG);
        AccessibilityNodeInfo root = findRootFor(dsPkg);
        if (root == null) {
            return -1;
        }
        int h = screenHeight();
        int chipRow = -1;
        for (AccessibilityNodeInfo n : flatten(root, 7000)) {
            String t = lower(n.getText());
            String d = lower(n.getContentDescription());
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (b.height() <= 0) {
                continue;
            }
            // Поле ввода и его подсказка — самый надёжный ориентир строки.
            if (isEditField(n) && b.width() > 200) {
                return Math.round(b.exactCenterY());
            }
            if (t.startsWith("напишите") || d.startsWith("напишите")
                    || t.startsWith("введите ваш вопрос") || d.startsWith("введите ваш вопрос")) {
                return Math.round(b.exactCenterY());
            }
            if ((t.equals("поиск") || t.equals("рассуждение"))
                    && b.exactCenterY() > h * 0.45f) {
                chipRow = Math.round(b.exactCenterY());
            }
        }
        return chipRow;
    }

    private boolean tapChatboxSend() {
        String dsPkg = prefs().getString(KEY_CHATBOX_PKG, DEFAULT_CHATBOX_PKG);
        AccessibilityNodeInfo root = findRootFor(dsPkg);
        if (root == null) {
            return false;
        }

        // У Chatbox кнопка отправки — элемент WebView: ACTION_CLICK проходит
        // «успешно», но React-обработчик не срабатывает. Поэтому жмём
        // настоящим жестом по центру кнопки.
        AccessibilityNodeInfo label = null;
        int labelTop = -1;
        for (AccessibilityNodeInfo n : flatten(root, 8000)) {
            if (!isVisible(n) || isEditField(n)) {
                continue;
            }
            String d = lower(n.getContentDescription());
            String t = lower(n.getText());
            if (!(d.equals("отправить") || d.startsWith("отправить")
                    || d.equals("send") || t.equals("отправить"))) {
                continue;
            }
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (b.width() <= 0 || b.height() <= 0) {
                continue;
            }
            if (b.top > labelTop) {
                labelTop = b.top;
                label = n;
            }
        }
        if (label != null) {
            Rect b = new Rect();
            label.getBoundsInScreen(b);
            Log.i(TAG, "Тап по кнопке отправки Chatbox " + b);
            return tap(b.exactCenterX(), b.exactCenterY());
        }

        // Запасной путь: самая правая кликабельная кнопка в строке ввода.
        int h = screenHeight();
        int rowY = chatboxComposerRowY();
        AccessibilityNodeInfo best = null;
        int bestX = -1;
        for (AccessibilityNodeInfo n : flatten(root, 7000)) {
            if (!n.isClickable() || !isVisible(n) || isEditField(n)) {
                continue;
            }
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (rowY > 0) {
                if (Math.abs(b.exactCenterY() - rowY) > 150) {
                    continue;
                }
            } else if (b.exactCenterY() < h * 0.70f) {
                continue;
            }
            String d = lower(n.getContentDescription());
            String t = lower(n.getText());
            if (d.contains("прикрепить") || d.contains("голос")
                    || d.contains("озвуч") || d.contains("настройк")
                    || d.contains("рассужд") || d.contains("поиск")
                    || d.contains("новый чат") || d.contains("панель")
                    || d.contains("режим чата") || d.contains("chatbox")
                    || t.contains("chatbox ai") || t.startsWith("chatbox-ai")) {
                continue;
            }
            if (b.exactCenterX() > bestX) {
                bestX = b.exactCenterX() > 0 ? Math.round(b.exactCenterX()) : -1;
                best = n;
            }
        }
        if (best == null) {
            return false;
        }
        Rect bb = new Rect();
        best.getBoundsInScreen(bb);
        Log.i(TAG, "Тап по кнопке отправки Chatbox (запасной) " + bb);
        return tap(bb.exactCenterX(), bb.exactCenterY());
    }

    /**
     * Достаёт из дерева Chatbox только итоговое письмо: находит последний
     * узел, начинающийся с «Тема:», и берёт текст начиная с него. Рассуждения
     * модели и служебные кнопки при этом отбрасываются.
     *
     * В WebView Chatbox ответ разбит на отдельные узлы, а всё, что выше
     * видимой области, приходит с нулевой высотой. Поэтому идём в порядке
     * документа (он совпадает с порядком чтения), а не по координатам.
     */
    private String extractChatboxAnswer(AccessibilityNodeInfo root) {
        if (root == null) {
            return null;
        }
        List<String> items = new ArrayList<>();
        for (AccessibilityNodeInfo n : flatten(root, 9000)) {
            CharSequence cs = n.getText();
            if (cs == null) {
                continue;
            }
            String t = cs.toString().trim();
            if (t.length() < 2 || isChatboxChrome(t) || isPartOfSentMessage(t)) {
                continue;
            }
            t = stripTokenFooter(t);
            if (t.length() < 2) {
                continue;
            }
            items.add(t);
        }
        if (items.isEmpty()) {
            return null;
        }

        int anchor = -1;
        for (int i = 0; i < items.size(); i++) {
            if (themeIndex(items.get(i)) >= 0) {
                anchor = i;
            }
        }

        int start = anchor;
        boolean skipHeaderNode = false;
        lpAnswerHadHeader = anchor >= 0;
        if (start < 0) {
            // Модель может начать письмо заголовком «Сопроводительное письмо»
            // вместо строки «Тема:». Тогда заголовок тоже отбрасываем.
            for (int i = 0; i < items.size(); i++) {
                if (isLetterHeader(items.get(i))) {
                    start = i + 1;
                    skipHeaderNode = true;
                    lpAnswerHadHeader = true;
                }
            }
        }
        if (start < 0) {
            // Совсем без заголовка — ищем начало письма по приветствию.
            for (int i = 0; i < items.size(); i++) {
                if (looksLikeLetter(items.get(i))) {
                    start = i;
                }
            }
        }
        if (start < 0) {
            return null;
        }
        if (skipHeaderNode && start >= items.size()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < items.size(); i++) {
            String t = items.get(i);
            if (i == start) {
                int at = themeIndex(t);
                if (at > 0) {
                    t = t.substring(at);
                }
            }
            if (t.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t);
        }
        String result = stripTokenFooter(sb.toString().trim());
        if (result.length() > 80) {
            Log.i(TAG, "Хвост письма: " + result.substring(result.length() - 80));
        }
        return result.isEmpty() ? null : result;
    }

    /** В чате есть осмысленный длинный текст (ответ или рассуждение). */
    private boolean hasConversationText(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        for (AccessibilityNodeInfo n : flatten(root, 7000)) {
            if (!isVisible(n)) {
                continue;
            }
            CharSequence cs = n.getText();
            if (cs == null) {
                continue;
            }
            String t = cs.toString().trim();
            if (t.length() < 120 || isChatboxChrome(t) || isPartOfSentMessage(t)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** Заголовок письма без «Тема:» — например, «Сопроводительное письмо». */
    private static boolean isLetterHeader(String text) {
        String t = lower(text);
        return t.equals("сопроводительное письмо")
                || t.startsWith("сопроводительное письмо:")
                || t.startsWith("сопроводительное письмо\n");
    }

    /** Позиция «Тема:» (в том числе «Тема письма:») в тексте или -1. */
    private static int themeIndex(String text) {
        if (text == null) {
            return -1;
        }
        String low = text.toLowerCase(Locale.ROOT);
        int best = -1;
        String[] markers = {"тема:", "тема письма:", "тема :"};
        for (String marker : markers) {
            int i = low.lastIndexOf(marker);
            if (i > best) {
                best = i;
            }
        }
        return best;
    }

    /**
     * Оставляет письмо, начиная с нового абзаца после строки «Тема: …».
     * Если разбить не получается, возвращает текст как есть.
     */
    private static String letterFromTheme(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        int at = themeIndex(t);
        if (at < 0) {
            return t;
        }
        String rest = t.substring(at);
        int nl = rest.indexOf('\n');
        if (nl < 0) {
            return t;
        }
        String letter = rest.substring(nl + 1).trim();
        return letter.length() < 10 ? t : letter;
    }

    private boolean isPartOfSentMessage(String text) {
        if (lpSentMessage == null || lpSentMessage.isEmpty()) {
            return false;
        }
        String t = text.trim();
        if (t.length() < 3) {
            return false;
        }
        if (lpSentMessage.contains(t)) {
            return true;
        }
        String head = t.length() > 40 ? t.substring(0, 40) : t;
        return lpSentMessage.contains(head);
    }

    private static boolean isChatboxChrome(String text) {
        String t = lower(text);
        if (t.length() > 20000) {
            return true;
        }
        return t.equals("копировать") || t.equals("скопировать")
                || t.equals("поделиться") || t.equals("еще") || t.equals("ещё")
                || t.equals("рассуждение") || t.equals("поиск")
                || t.equals("стоп") || t.equals("остановить")
                || t.equals("хороший ответ") || t.equals("плохой ответ")
                || t.equals("новый чат") || t.equals("chatbox")
                || t.equals("прикрепить файлы") || t.equals("отправить")
                || t.equals("ответить снова") || t.equals("редактировать")
                || t.equals("режим чата") || t.equals("untitled")
                || t.startsWith("chatboxai")
                || t.startsWith("chatbox-ai image icon")
                || t.startsWith("частичная оценка")
                || t.startsWith("полная оценка")
                || t.startsWith("you are a helpful assistant")
                || t.startsWith("дождитесь завершения")
                || t.startsWith("ai может ошибаться")
                || isTokenCountLine(t)
                || t.startsWith("размышлял") || t.startsWith("рассужд")
                || t.startsWith("думал") || t.startsWith("глубокое размышление")
                || t.startsWith("напишите или удерживайте")
                || t.startsWith("напишите сообщение")
                || t.startsWith("введите ваш вопрос")
                || t.startsWith("создано chatbox")
                || t.equals("читать вслух") || t.equals("повторить")
                || t.equals("развернуть") || t.equals("свернуть")
                || t.equals("перейти на голос")
                || t.equals("автоматическое чтение вслух")
                || t.equals("открыть боковую панель");
    }

    /** Строка вида «· 4545 tokens», «4545 tokens», «~3K tokens». */
    private static boolean isTokenCountLine(String text) {
        String t = lower(text);
        if (t.length() > 40 || (!t.endsWith("tokens") && !t.endsWith("token"))) {
            return false;
        }
        return t.matches(".*\\d.*");
    }

    /**
     * Убирает у текста хвостовые строки со счётчиком токенов Chatbox.
     * Иногда WebView отдаёт ответ и метаданные одним узлом — тогда счётчик
     * «· N tokens» оказывается в конце письма.
     */
    private static String stripTokenFooter(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        int end = lines.length;
        while (end > 0 && isTokenCountLine(lines[end - 1])) {
            end--;
        }
        String body = text;
        if (end != lines.length) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < end; i++) {
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append(lines[i]);
            }
            body = sb.toString();
        }
        // На случай, если счётчик приклеен к последней строке без перевода строки.
        return body
                .replaceAll("(?is)\\s*(?:[·•~\\-–—]\\s*)?\\d[\\d\\s.,kK]{0,12}\\s*tokens?\\s*$", "")
                .trim();
    }

    // ---------------- служебные действия ----------------

    /** Экранная клавиатура реально показана (а не просто свёрнута). */
    private boolean isImeVisible() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    continue;
                }
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) {
                    continue;
                }
                Rect b = new Rect();
                r.getBoundsInScreen(b);
                if (b.height() > 0) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private int screenHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private int screenWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private void logScreen(String pkg, int maxLines) {
        try {
            AccessibilityNodeInfo root = findRootFor(pkg);
            if (root == null) {
                BotLog.add("Экран " + pkg + ": окно не найдено");
                return;
            }
            StringBuilder sb = new StringBuilder("Экран " + pkg + ":");
            int count = 0;
            for (AccessibilityNodeInfo n : flatten(root, 7000)) {
                String t = n.getText() == null ? "" : n.getText().toString().trim();
                String d = n.getContentDescription() == null
                        ? "" : n.getContentDescription().toString().trim();
                String s = t.isEmpty() ? d : t;
                if (s.isEmpty() || sb.indexOf(s) >= 0) {
                    continue;
                }
                sb.append(" | ").append(s);
                if (++count >= maxLines) {
                    break;
                }
            }
            BotLog.add(sb.toString());
        } catch (Throwable t) {
            BotLog.add("Не удалось прочитать экран: " + t);
        }
    }

    private void readClipboardViaRelay(String purpose) {
        try {
            Intent i = new Intent(this, ClipboardRelayActivity.class);
            i.putExtra(ClipboardRelayActivity.EXTRA_PURPOSE, purpose);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(i);
        } catch (Throwable t) {
            BotLog.add("Не удалось прочитать буфер обмена: " + t);
        }
    }

    private void setClipboard(String text) {
        try {
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("hhbot", text));
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean setNodeText(AccessibilityNodeInfo node, String text) {
        if (node == null) {
            return false;
        }
        try {
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Throwable t) {
            return false;
        }
    }

    private void bringHhToFront() {
        String pkg = prefs().getString(KEY_PACKAGE, "ru.hh.android");
        try {
            // В новых версиях hh.ru имени ApplicantRootActivity уже нет,
            // поэтому сначала берём обычный intent запуска приложения.
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) {
                String component = prefs().getString(KEY_HH_ACTIVITY, DEFAULT_HH_ACTIVITY);
                ComponentName cn = ComponentName.unflattenFromString(component);
                if (cn == null) {
                    BotLog.add("Не удалось открыть hh.ru: активность запуска не найдена");
                    return;
                }
                intent = new Intent();
                intent.setComponent(cn);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);
        } catch (Throwable t) {
            BotLog.add("Не удалось открыть hh.ru: " + t);
        }
    }

    private AccessibilityNodeInfo findRootFor(String pkg) {
        if (TextUtils.isEmpty(pkg)) {
            return null;
        }
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && pkg.equals(String.valueOf(root.getPackageName()))) {
                return root;
            }
        } catch (Throwable ignored) {
        }
        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null && pkg.equals(String.valueOf(root.getPackageName()))) {
                    return root;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Пакет активного (верхнего) окна. */
    private String topPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            return root == null ? "" : String.valueOf(root.getPackageName());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean tap(float x, float y) {
        try {
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

    private boolean longPress(float x, float y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 750))
                    .build();
            return dispatchGesture(gesture, null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean swipe(float x1, float y1, float x2, float y2, long ms) {
        try {
            Path path = new Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, ms))
                    .build();
            return dispatchGesture(gesture, null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
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
