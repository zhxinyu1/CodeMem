package com.codemem.workflow;

import com.codemem.knowledge.CitationView;
import com.codemem.knowledge.RetrievedChunk;
import com.codemem.memory.longterm.MemoryItemView;
import com.codemem.memory.working.WorkingMessage;
import com.codemem.state.ProjectState;

import java.util.ArrayList;
import java.util.List;

/**
 * 单轮共享黑板：角色之间只通过黑板协作，不传递自由文本对话历史。
 *
 * <p>写入权限：Mentor 只能写 mentorDraft；Reviewer 只能写 reviewerCritique；
 * 引用只能来自 retrievedNotes，禁止编造笔记路径。</p>
 */
public class Blackboard {

    private final String learnerId;
    private final String projectId;
    private final Long conversationId;
    private final String traceId;
    private final String userQuery;
    private final ProjectState state;
    private final List<RetrievedChunk> retrievedNotes;
    private final List<WorkingMessage> retrievedMemories;
    private final List<MemoryItemView> longTermMemories;
    private final List<MemoryItemView> instructions;
    private final String profileSummary;
    private final List<CitationView> citations;
    private final BudgetGuard budget;

    private String mentorDraft = "";
    private String reviewerCritique = "";

    public Blackboard(String learnerId,
                      String projectId,
                      Long conversationId,
                      String traceId,
                      String userQuery,
                      ProjectState state,
                      List<RetrievedChunk> retrievedNotes,
                      List<WorkingMessage> retrievedMemories,
                      List<MemoryItemView> longTermMemories,
                      List<MemoryItemView> instructions,
                      String profileSummary,
                      List<CitationView> citations,
                      BudgetGuard budget) {
        this.learnerId = learnerId;
        this.projectId = projectId;
        this.conversationId = conversationId;
        this.traceId = traceId;
        this.userQuery = userQuery;
        this.state = state;
        this.retrievedNotes = retrievedNotes == null ? List.of() : List.copyOf(retrievedNotes);
        this.retrievedMemories = retrievedMemories == null ? List.of() : List.copyOf(retrievedMemories);
        this.longTermMemories = longTermMemories == null ? List.of() : List.copyOf(longTermMemories);
        this.instructions = instructions == null ? List.of() : List.copyOf(instructions);
        this.profileSummary = profileSummary == null ? "" : profileSummary;
        this.citations = citations == null ? List.of() : List.copyOf(citations);
        this.budget = budget == null ? new BudgetGuard() : budget;
    }

    /** 兼容旧调用：不带全局指令时按空列表处理。 */
    public Blackboard(String learnerId,
                      String projectId,
                      Long conversationId,
                      String traceId,
                      String userQuery,
                      ProjectState state,
                      List<RetrievedChunk> retrievedNotes,
                      List<WorkingMessage> retrievedMemories,
                      List<MemoryItemView> longTermMemories,
                      String profileSummary,
                      List<CitationView> citations,
                      BudgetGuard budget) {
        this(learnerId, projectId, conversationId, traceId, userQuery, state, retrievedNotes,
                retrievedMemories, longTermMemories, List.of(), profileSummary, citations, budget);
    }

    public String learnerId() {
        return learnerId;
    }

    public String projectId() {
        return projectId;
    }

    public Long conversationId() {
        return conversationId;
    }

    public String traceId() {
        return traceId;
    }

    public String userQuery() {
        return userQuery;
    }

    public ProjectState state() {
        return state;
    }

    public List<RetrievedChunk> retrievedNotes() {
        return retrievedNotes;
    }

    public List<WorkingMessage> retrievedMemories() {
        return retrievedMemories;
    }

    public List<MemoryItemView> longTermMemories() {
        return longTermMemories;
    }

    /** 用户设定的全局指令，每轮必须先遵守。 */
    public List<MemoryItemView> instructions() {
        return instructions;
    }

    public String profileSummary() {
        return profileSummary;
    }

    public List<CitationView> citations() {
        return citations;
    }

    public BudgetGuard budget() {
        return budget;
    }

    public String mentorDraft() {
        return mentorDraft;
    }

    public void writeMentorDraft(String draft) {
        this.mentorDraft = draft == null ? "" : draft;
    }

    public String reviewerCritique() {
        return reviewerCritique;
    }

    public void writeReviewerCritique(String critique) {
        this.reviewerCritique = critique == null ? "" : critique;
    }

    /**
     * 只保留真实检索到的笔记路径，防止引用被编造。
     */
    public List<CitationView> verifiedCitations() {
        if (citations.isEmpty()) {
            return List.of();
        }
        List<String> allowedPaths = new ArrayList<>();
        for (RetrievedChunk note : retrievedNotes) {
            if (note.path() != null) {
                allowedPaths.add(note.path());
            }
        }
        return citations.stream()
                .filter(citation -> citation.path() != null && allowedPaths.contains(citation.path()))
                .toList();
    }
}