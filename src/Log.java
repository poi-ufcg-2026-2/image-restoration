import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class Log {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static void info(String format, Object... args) {
        write("INFO", format, args);
    }

    public static void warn(String format, Object... args) {
        write("WARN", format, args);
    }

    public static void error(String format, Object... args) {
        write("ERROR", format, args);
    }

    private static void write(String level, String format, Object... args) {
        System.out.printf(Locale.ROOT, "%s %-5s [%s] %s%n", LocalDateTime.now().format(TIMESTAMP), level,
                Thread.currentThread().getName(), String.format(Locale.ROOT, format, args));
    }
}
