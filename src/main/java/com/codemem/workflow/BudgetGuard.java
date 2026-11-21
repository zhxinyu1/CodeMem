package com.codemem.workflow;

/**
 * 单轮预算硬约束：token、单角色超时、全链路超时、修订次数。
 *
 * <p>超预算时的处理顺序固定为：截断上下文 -> 跳过修订 -> 输出草稿并标注未完成审查，绝不循环重试。</p>
 */
public class BudgetGuard {

    public static final int TOTAL_TOKENS = 8_000;
    public static final int MENTOR_TOKENS = 1_200;
    public static final int REVIEWER_TOKENS = 600;
    public static final long ROLE_TIMEOUT_MS = 20_000L;
    public static final long TOTAL_TIMEOUT_MS = 45_000L;
    public static final int MAX_REVISIONS = 1;

    private final long startedAt;
    private final int totalTokens;
    private final int maxRevisions;

    private int usedTokens;
    private int revisions;

    public BudgetGuard() {
        this(System.currentTimeMillis(), TOTAL_TOKENS, MAX_REVISIONS);
    }

    public BudgetGuard(long startedAt) {
        this(startedAt, TOTAL_TOKENS, MAX_REVISIONS);
    }

    public BudgetGuard(long startedAt, int totalTokens, int maxRevisions) {
        this.startedAt = startedAt;
        this.totalTokens = totalTokens;
        this.maxRevisions = maxRevisions;
    }

    public boolean canRevise() {
        return revisions < maxRevisions && !expired();
    }

    public void onRevision() {
        revisions++;
    }

    public int revisions() {
        return revisions;
    }

    public void addTokens(int tokens) {
        if (tokens > 0) {
            usedTokens += tokens;
        }
    }

    public int usedTokens() {
        return usedTokens;
    }

    public int mentorLimit() {
        return Math.min(MENTOR_TOKENS, Math.max(0, totalTokens - usedTokens));
    }

    public int reviewerLimit() {
        return Math.min(REVIEWER_TOKENS, Math.max(0, totalTokens - usedTokens));
    }

    public boolean tokenBudgetExhausted() {
        return usedTokens >= totalTokens;
    }

    public boolean expired() {
        return elapsed() >= TOTAL_TIMEOUT_MS;
    }

    public long elapsed() {
        return System.currentTimeMillis() - startedAt;
    }

    public long remainingMillis() {
        return Math.max(0L, TOTAL_TIMEOUT_MS - elapsed());
    }

    /**
     * 按 token 上限截断上下文：中文按 1 token ≈ 1.5 字符估算。
     */
    public String truncate(String text, int maxTokens) {
        if (text == null) {
            return "";
        }
        int maxChars = Math.max(0, (int) Math.floor(maxTokens * 1.5));
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n……（上下文已按预算截断）";
    }
}