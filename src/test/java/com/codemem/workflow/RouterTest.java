package com.codemem.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterTest {

    private final Router router = new Router();

    @Test
    void singleForPlainConceptQuestion() {
        RoutingDecision decision = router.route("红黑树的左旋是什么？举个例子", false);
        assertEquals(WorkflowMode.SINGLE, decision.mode());
    }

    @Test
    void dualForStudyPlan() {
        RoutingDecision decision = router.route("帮我制定一个两周的学习计划", false);
        assertEquals(WorkflowMode.DUAL, decision.mode());
        assertTrue(decision.reason().contains("计划"));
    }

    @Test
    void dualForCrossSessionReference() {
        RoutingDecision decision = router.route("上次那个左旋我还是写不对", true);
        assertEquals(WorkflowMode.DUAL, decision.mode());
        assertTrue(decision.reason().contains("跨会话"));
    }

    @Test
    void dualForExplicitReviewRequest() {
        RoutingDecision decision = router.route("帮我严格审查这段红黑树代码", false);
        assertEquals(WorkflowMode.DUAL, decision.mode());
        assertTrue(decision.reason().contains("审查"));
    }

    @Test
    void dualForDiagnosisSignal() {
        RoutingDecision decision = router.route("我卡在红黑树左旋，指针总写错", false);
        assertEquals(WorkflowMode.DUAL, decision.mode());
        assertTrue(decision.reason().contains("诊断") || decision.reason().contains("卡在"));
    }

    @Test
    void dualForCrossSessionSynonym() {
        assertEquals(WorkflowMode.DUAL, router.route("接着上次的并查集，说说加权合并", true).mode());
        assertEquals(WorkflowMode.DUAL, router.route("上次讲的 B+树适合什么场景", true).mode());
    }

    @Test
    void singleWhenNoSignal() {
        RoutingDecision decision = router.route("今天先聊到这里", true);
        assertEquals(WorkflowMode.SINGLE, decision.mode());
    }
}