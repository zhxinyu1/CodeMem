package com.codemem.knowledge;

import java.util.Set;

/**
 * 中文关键词检索的 tsquery 构造：与知识库、长期记忆共用，避免两套停用词漂移。
 */
public final class TsQueryBuilder {

    private static final Set<String> STOPWORDS = Set.of(
            "我", "你", "他", "她", "它", "的", "了", "在", "是", "有", "和", "与", "及", "或",
            "就", "都", "也", "很", "再", "又", "把", "被", "让", "给", "对", "从", "到", "为",
            "一个", "这个", "那个", "什么", "怎么", "如何", "为什么", "哪", "哪里", "哪个",
            "吗", "呢", "吧", "啊", "呀", "一下", "帮我", "请问", "告诉", "根据", "笔记",
            "应该", "可以", "需要", "现在", "然后", "如果", "但是", "因为", "所以", "进行",
            "使用", "通过", "以及", "还有", "就是", "不是", "没有", "已经", "一直", "比较",
            "非常", "特别", "主要", "相关", "内容", "问题", "时候", "方面", "东西", "地方",
            "感觉", "觉得", "知道", "看到", "这里", "那里"
    );

    private TsQueryBuilder() {
    }

    public static String build(String tokenized) {
        if (tokenized == null || tokenized.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String token : tokenized.split("\\s+")) {
            StringBuilder safe = new StringBuilder();
            token.codePoints()
                    .filter(Character::isLetterOrDigit)
                    .forEach(safe::appendCodePoint);
            if (safe.isEmpty()) {
                continue;
            }
            String term = safe.toString();
            if (STOPWORDS.contains(term)) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(term).append(":*");
        }
        return builder.toString();
    }
}