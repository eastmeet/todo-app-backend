package simple.todo.app;

import com.p6spy.engine.logging.Category;
import com.p6spy.engine.spy.P6SpyOptions;
import com.p6spy.engine.spy.appender.MessageFormattingStrategy;
import jakarta.annotation.PostConstruct;
import java.util.Locale;
import org.hibernate.engine.jdbc.internal.FormatStyle;
import org.springframework.context.annotation.Configuration;

@Configuration
public class P6SpyFormatter implements MessageFormattingStrategy {

    @PostConstruct
    public void setLogMessageFormat() {
        P6SpyOptions.getActiveInstance().setLogMessageFormat(this.getClass().getName());
    }

    @Override
    public String formatMessage(int connectionId, String now, long elapsed, String category, String prepared, String sql, String url) {
        String repositoryMethod = findRepositoryMethod();
        String formattedSql = formatSql(category, sql);

        return String.format("[%s] | %d ms | Repository: %s | %s",
            category, elapsed, repositoryMethod, formattedSql);
    }

    private String formatSql(String category, String sql) {
        if (sql != null && !sql.trim().isEmpty() && Category.STATEMENT.getName().equals(category)) {
            String trimmedSQL = sql.trim().toLowerCase(Locale.ROOT);
            if (trimmedSQL.startsWith("create") || trimmedSQL.startsWith("alter") || trimmedSQL.startsWith("comment")) {
                sql = FormatStyle.DDL.getFormatter().format(sql);
            } else {
                sql = FormatStyle.BASIC.getFormatter().format(sql);
            }
            return sql;
        }
        return sql;
    }

    private String findRepositoryMethod() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();

        // 더 정확한 Repository 메서드 찾기
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            String methodName = element.getMethodName();

            // Repository 패턴들을 확인
            if (className.contains("Repository") ||
                className.contains("repository") ||
                className.endsWith("Repo")) {

                // 클래스명을 간단하게 변환
                String simpleName = className.substring(className.lastIndexOf('.') + 1);
                return simpleName + "." + methodName + "()";
            }
        }

        // JPA Repository 호출 패턴도 확인
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (className.contains("jpa") || className.contains("data")) {
                String simpleName = className.substring(className.lastIndexOf('.') + 1);
                return "JPA." + simpleName + "." + element.getMethodName() + "()";
            }
        }

        return "unknown";
    }
}
