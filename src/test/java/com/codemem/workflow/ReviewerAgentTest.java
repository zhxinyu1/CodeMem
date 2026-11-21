package com.codemem.workflow;

import com.codemem.ai.MockChatEngine;
import com.codemem.knowledge.CitationView;
import com.codemem.knowledge.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewerAgentTest {

    @Test
    void parsesPassVerdict() {
        ReviewResult result = ReviewerAgent.parse("""
                VERDICT: PASS
                ISSUES:
                EVIDENCE:
                """);
        assertTrue(result.pass());
    }

    @Test
    void parsesRejectVerdictWithIssues() {
        ReviewResult result = ReviewerAgent.parse("""
                VERDICT: REJECT
                ISSUES:
                - 草稿声称“笔记里已经确认过”，但本轮没有引用
                - 代码示例缺少边界判断
                EVIDENCE:
                - knowledgeCount=0
                """);
        assertFalse(result.pass());
        assertEquals(2, result.issues().size());
        assertEquals(List.of("knowledgeCount=0"), result.evidence());
    }

    @Test
    void treatsUnparseableOutputAsPass() {
        assertTrue(ReviewerAgent.parse("模型没有按格式返回").pass());
        assertTrue(ReviewerAgent.parse("").pass());
    }

    @Test
    void mockRejectsUnsupportedClaimWithoutEvidence() {
        Blackboard board = boardWithoutNotes();
        board.writeMentorDraft("这条结论我在笔记里已经确认过，你可以直接照做。");
        ReviewResult result = new ReviewerAgent(new MockChatEngine()).review(board);
        assertFalse(result.pass());
        assertTrue(result.issues().get(0).contains("无证据断言"));
    }

    @Test
    void mockPassesClaimThatIsBackedByNotes() {
        RetrievedChunk note = new RetrievedChunk("c1", "学习笔记/红黑树.md", "红黑树", "红黑树 > 左旋",
                "左旋四步：x = h.right; h.right = x.left; ...", 0.9);
        Blackboard board = new Blackboard("u1", "p1", 1L, "trace", "左旋怎么写",
                null, List.of(note), List.of(), List.of(), "画像", List.of(CitationView.from(note)), new BudgetGuard());
        board.writeMentorDraft("依据：本轮检索到 1 条笔记片段（见引用）。");
        assertTrue(new ReviewerAgent(new MockChatEngine()).review(board).pass());
    }

    private Blackboard boardWithoutNotes() {
        return new Blackboard("u1", "p1", 1L, "trace", "帮我制定一个两周的学习计划",
                null, List.of(), List.of(), List.of(), "画像", List.of(), new BudgetGuard());
    }
}