package ru.hhbot;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Общий журнал работы. Живёт в памяти процесса: MainActivity и служба
 * доступности работают в одном процессе, поэтому экран сразу видит все записи.
 */
public final class BotLog {

    private static final int MAX_LINES = 300;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private static long version = 0;

    private BotLog() {
    }

    public static synchronized void add(String message) {
        String line = TIME.format(new Date()) + "  " + message;
        LINES.addLast(line);
        while (LINES.size() > MAX_LINES) {
            LINES.removeFirst();
        }
        version++;
    }

    public static synchronized void clear() {
        LINES.clear();
        version++;
    }

    public static synchronized String text() {
        if (LINES.isEmpty()) {
            return "Журнал пуст.";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : LINES) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public static synchronized long version() {
        return version;
    }
}
