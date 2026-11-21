package com.codemem.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetGuardTest {

    @Test
    void allowsOnlyOneRevision() {
        BudgetGuard budget = new BudgetGuard();
        assertTrue(budget.canRevise());
        budget.onRevision();
        assertEquals(1, budget.revisions());
        assertFalse(budget.canRevise());
    }

    @Test
    void refusesRevisionWhenTotalTimeoutExceeded() {
        BudgetGuard budget = new BudgetGuard(System.currentTimeMillis() - BudgetGuard.TOTAL_TIMEOUT_MS - 1_000L);
        assertTrue(budget.expired());
        assertFalse(budget.canRevise());
        assertEquals(0L, budget.remainingMillis());
    }

    @Test
    void truncatesContextByTokenBudget() {
        BudgetGuard budget = new BudgetGuard();
        String longText = "红".repeat(5_000);
        String truncated = budget.truncate(longText, 100);
        assertTrue(truncated.length() < longText.length());
        assertTrue(truncated.endsWith("（上下文已按预算截断）"));
    }

    @Test
    void accumulatesTokens() {
        BudgetGuard budget = new BudgetGuard();
        budget.addTokens(1_500);
        assertEquals(1_500, budget.usedTokens());
        assertTrue(budget.mentorLimit() <= BudgetGuard.MENTOR_TOKENS);
    }
}