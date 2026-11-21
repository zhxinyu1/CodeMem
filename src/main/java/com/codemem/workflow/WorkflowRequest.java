package com.codemem.workflow;

import com.codemem.knowledge.CitationView;
import com.codemem.knowledge.RetrievedChunk;
import com.codemem.memory.longterm.MemoryItemView;
import com.codemem.memory.working.WorkingMessage;
import com.codemem.state.ProjectState;

import java.util.List;
import java.util.function.Consumer;

/**
 * 一轮工作流请求。上下文全部由 ChatService 预先取好并注入，编排层不再访问数据库。
 *
 * @param stepListener 可选：SSE 进度回调，只用于前端展示，不参与决策
 */
public record WorkflowRequest(
        String learnerId,
        String projectId,
        Long conversationId,
        String turnId,
        String traceId,
        String userQuery,
        RoutingDecision decision,
        ProjectState state,
        List<RetrievedChunk> notes,
        List<WorkingMessage> memories,
        List<MemoryItemView> longTermMemories,
        String profileSummary,
        List<CitationView> citations,
        Consumer<AgentStepView> stepListener,
        List<MemoryItemView> instructions
) {

    /** 兼容旧调用：不带全局指令时按空列表处理。 */
    public WorkflowRequest(String learnerId,
                           String projectId,
                           Long conversationId,
                           String turnId,
                           String traceId,
                           String userQuery,
                           RoutingDecision decision,
                           ProjectState state,
                           List<RetrievedChunk> notes,
                           List<WorkingMessage> memories,
                           List<MemoryItemView> longTermMemories,
                           String profileSummary,
                           List<CitationView> citations,
                           Consumer<AgentStepView> stepListener) {
        this(learnerId, projectId, conversationId, turnId, traceId, userQuery, decision, state, notes,
                memories, longTermMemories, profileSummary, citations, stepListener, List.of());
    }

    public WorkflowMode mode() {
        return decision == null ? WorkflowMode.SINGLE : decision.mode();
    }

    public Consumer<AgentStepView> listener() {
        return stepListener == null ? step -> {
        } : stepListener;
    }
}