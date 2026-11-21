package com.codemem.knowledge;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationValidatorTest {

    private static final Predicate<String> PATHS = Set.of("学习笔记/红黑树.md", "学习笔记/堆.md")::contains;

    private static final java.util.function.BiPredicate<String, String> HEADINGS =
            (path, heading) -> Set.of("红黑树 > 左旋", "堆 > 下沉").contains(heading);

    @Test
    void keepsValidCitation() {
        CitationValidator.Result result = CitationValidator.rewrite(
                "左旋第一步是 x = h.right [笔记: 学习笔记/红黑树.md#红黑树 > 左旋]", PATHS, HEADINGS);
        assertEquals(1, result.total());
        assertEquals(0, result.degraded());
        assertEquals(0, result.dropped());
        assertTrue(result.text().contains("#红黑树 > 左旋"));
    }

    @Test
    void degradesWhenHeadingDrifts() {
        CitationValidator.Result result = CitationValidator.rewrite(
                "依据 [笔记: 学习笔记/红黑树.md#2-3树] 可知", PATHS, HEADINGS);
        assertEquals(1, result.degraded());
        assertEquals(0, result.dropped());
        assertTrue(result.text().contains("[笔记: 学习笔记/红黑树.md]"));
        assertFalse(result.text().contains("#2-3树"));
    }

    @Test
    void dropsUnknownPath() {
        CitationValidator.Result result = CitationValidator.rewrite(
                "依据 [笔记: 学习笔记/不存在.md#任意] 可知", PATHS, HEADINGS);
        assertEquals(1, result.dropped());
        assertEquals(0, result.degraded());
        assertFalse(result.text().contains("不存在.md#"));
        assertTrue(result.text().contains("（引用不可用）"));
    }

    @Test
    void handlesMultipleCitations() {
        CitationValidator.Result result = CitationValidator.rewrite(
                "A [笔记: 学习笔记/红黑树.md#红黑树 > 左旋] B [笔记: 学习笔记/堆.md#堆 > 下沉] C", PATHS, HEADINGS);
        assertEquals(2, result.total());
        assertEquals(0, result.degraded());
        assertEquals(0, result.dropped());
        assertTrue(result.text().startsWith("A "));
        assertTrue(result.text().endsWith(" C"));
    }

    @Test
    void leavesPlainTextUntouched() {
        CitationValidator.Result result = CitationValidator.rewrite("没有引用的回答", PATHS, HEADINGS);
        assertEquals(0, result.total());
        assertEquals("没有引用的回答", result.text());
    }

    @Test
    void tolerantToSpacingWhenLookupNormalizes() {
        java.util.function.BiPredicate<String, String> normalized =
                (path, heading) -> Set.of("红黑树>左旋", "堆>下沉").contains(heading.replaceAll("\s+", ""));
        CitationValidator.Result result = CitationValidator.rewrite(
                "[笔记: 学习笔记/红黑树.md#红黑树>左旋]", PATHS, normalized);
        assertEquals(1, result.total());
        assertEquals(0, result.degraded());
    }
}