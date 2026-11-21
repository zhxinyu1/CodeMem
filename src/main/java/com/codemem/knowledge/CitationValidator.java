package com.codemem.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用校验：模型可能写出不存在的笔记路径或漂移的小节标题。
 *
 * <p>路径存在但标题不存在 -> 降级成 `[笔记: 路径]`；
 * 路径也不存在 -> 去掉引用片段，避免把不存在的笔记当依据。</p>
 */
@Component
public class CitationValidator {

    private static final Logger log = LoggerFactory.getLogger(CitationValidator.class);
    private static final Pattern CITATION = Pattern.compile("\\[笔记:\\s*([^#\\]\\n]+)#([^\\]\\n]+)\\]");

    private final JdbcTemplate postgresJdbc;
    private final PostgresSchemaInitializer schemaInitializer;

    public CitationValidator(@Qualifier("postgresJdbc") JdbcTemplate postgresJdbc,
                             PostgresSchemaInitializer schemaInitializer) {
        this.postgresJdbc = postgresJdbc;
        this.schemaInitializer = schemaInitializer;
    }

    public record Result(String text, int total, int degraded, int dropped) {
    }

    public Result validate(String answer) {
        if (answer == null || answer.isBlank() || !schemaInitializer.isAvailable()) {
            return new Result(answer, 0, 0, 0);
        }
        return rewrite(answer,
                path -> count("SELECT count(*) FROM knowledge_doc WHERE path = ? AND index_status = 'ACTIVE'", path) > 0,
                (path, heading) -> headingExists(path, heading));
    }

    private boolean headingExists(String path, String heading) {
        List<String> headings = postgresJdbc.queryForList("""
                SELECT c.heading_path
                FROM knowledge_chunk c
                JOIN knowledge_doc d ON d.id = c.doc_id
                WHERE d.path = ? AND d.index_status = 'ACTIVE'
                """, String.class, path);
        String normalized = normalize(heading);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String candidate : headings) {
            String value = normalize(candidate);
            if (value.contains(normalized) || normalized.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private int count(String sql, String path) {
        Integer value = postgresJdbc.queryForObject(sql, Integer.class, path);
        return value == null ? 0 : value;
    }

    /**
     * 纯文本改写，便于单测：pathExists / headingExists 由调用方提供。
     */
    static Result rewrite(String answer, Predicate<String> pathExists, BiPredicate<String, String> headingExists) {
        Matcher matcher = CITATION.matcher(answer);
        StringBuilder builder = new StringBuilder();
        int total = 0;
        int degraded = 0;
        int dropped = 0;
        int last = 0;
        while (matcher.find()) {
            total++;
            String path = matcher.group(1).trim();
            String heading = matcher.group(2).trim();
            builder.append(answer, last, matcher.start());
            if (!pathExists.test(path)) {
                dropped++;
                builder.append("（引用不可用）");
            } else if (!headingExists.test(path, heading)) {
                degraded++;
                builder.append("[笔记: ").append(path).append(']');
            } else {
                builder.append(matcher.group());
            }
            last = matcher.end();
        }
        builder.append(answer.substring(last));
        if (dropped > 0 || degraded > 0) {
            log.debug("Citation check: total={} degraded={} dropped={}", total, degraded, dropped);
        }
        return new Result(builder.toString(), total, degraded, dropped);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    /**
     * 便于日志与指标使用的摘要。
     */
    public static String summary(Result result) {
        return result == null ? "none"
                : "total=" + result.total() + " degraded=" + result.degraded() + " dropped=" + result.dropped();
    }

    static List<String> normalizeAll(List<String> values) {
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            normalized.add(normalize(value));
        }
        return normalized;
    }
}