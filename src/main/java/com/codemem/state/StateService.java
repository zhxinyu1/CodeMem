package com.codemem.state;

import com.codemem.profile.LearnerProfile;
import com.codemem.profile.ProfileService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class StateService {

    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> locks
            = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Set<String> ALLOWED_PATHS = Set.of(
            "goal", "stage", "mastered", "weakPoints", "currentBlocker",
            "decisions", "nextActions", "openQuestions", "preferences"
    );
    private static final Set<String> LIST_PATHS = Set.of(
            "mastered", "weakPoints", "decisions", "nextActions", "openQuestions"
    );
    private static final Set<String> OPS = Set.of("SET", "ADD", "REMOVE", "RESOLVE");

    private final StateSnapshotRepository snapshots;
    private final StateEventRepository events;
    private final ProfileService profileService;
    private final ObjectMapper objectMapper;

    public StateService(StateSnapshotRepository snapshots,
                        StateEventRepository events,
                        ProfileService profileService,
                        ObjectMapper objectMapper) {
        this.snapshots = snapshots;
        this.events = events;
        this.profileService = profileService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public synchronized ProjectState getOrCreate(String learnerId, String projectId) {
        String normalizedLearnerId = normalize(learnerId);
        String normalizedProjectId = normalize(projectId);

        return snapshots.findTopByLearnerIdAndProjectIdOrderByVersionDesc(
                        normalizedLearnerId, normalizedProjectId)
                .map(this::readSnapshot)
                .orElseGet(() -> {
                    ProjectState initial = initialState(normalizedLearnerId, normalizedProjectId);
                    saveSnapshot(initial);
                    return initial;
                });
    }

    @Transactional
    public ProjectState applyPatch(String learnerId,
                                   String projectId,
                                   StatePatch patch,
                                   String turnId,
                                   boolean correction) {
        String key = normalize(learnerId) + "|" + normalize(projectId);
        java.util.concurrent.locks.ReentrantLock lock = locks.computeIfAbsent(
                key, ignored -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            return applyPatchLocked(learnerId, projectId, patch, turnId, correction);
        } finally {
            lock.unlock();
        }
    }

    private ProjectState applyPatchLocked(String learnerId,
                                            String projectId,
                                            StatePatch patch,
                                            String turnId,
                                            boolean correction) {
        ProjectState current = getOrCreate(learnerId, projectId);
        validatePatch(patch, current);

        ObjectNode root = objectMapper.valueToTree(current);
        for (StateOp op : patch.ops()) {
            applyOp(root, op);
        }

        int nextVersion = current.version() + 1;
        Instant now = Instant.now();
        root.put("version", nextVersion);
        root.put("updatedAt", now.toString());

        List<String> mergedEvidence = mergeEvidence(current.evidenceRefs(), patch.ops());
        ArrayNode evidenceArray = root.putArray("evidenceRefs");
        mergedEvidence.forEach(evidenceArray::add);

        ProjectState patched;
        try {
            patched = objectMapper.treeToValue(root, ProjectState.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("invalid state patch: " + ex.getOriginalMessage(), ex);
        }
        ProjectState next = new ProjectState(
                current.learnerId(),
                current.projectId(),
                patched.goal(),
                patched.stage(),
                patched.mastered(),
                patched.weakPoints(),
                patched.currentBlocker(),
                patched.decisions(),
                patched.nextActions(),
                patched.openQuestions(),
                patched.preferences(),
                nextVersion,
                now,
                mergedEvidence
        );
        validateState(next);
        saveSnapshot(next);
        saveEvent(current, next, patch, mergedEvidence, turnId, correction);
        return next;
    }

    @Transactional
    public synchronized List<StateHistoryView> history(String learnerId, String projectId) {
        String normalizedLearnerId = normalize(learnerId);
        String normalizedProjectId = normalize(projectId);
        getOrCreate(normalizedLearnerId, normalizedProjectId);
        return snapshots.findByLearnerIdAndProjectIdOrderByVersionAsc(normalizedLearnerId, normalizedProjectId)
                .stream()
                .map(snapshot -> new StateHistoryView(
                        snapshot.getVersion(),
                        snapshot.getSummary(),
                        snapshot.getCreatedAt()
                ))
                .toList();
    }

    @Transactional
    public synchronized StateDiff diff(String learnerId, String projectId, int fromVersion, int toVersion) {
        String normalizedLearnerId = normalize(learnerId);
        String normalizedProjectId = normalize(projectId);
        StateSnapshot beforeSnapshot = snapshots.findByLearnerIdAndProjectIdAndVersion(
                        normalizedLearnerId, normalizedProjectId, fromVersion)
                .orElseThrow(() -> new IllegalArgumentException("state version not found: " + fromVersion));
        StateSnapshot afterSnapshot = snapshots.findByLearnerIdAndProjectIdAndVersion(
                        normalizedLearnerId, normalizedProjectId, toVersion)
                .orElseThrow(() -> new IllegalArgumentException("state version not found: " + toVersion));

        ProjectState before = readSnapshot(beforeSnapshot);
        ProjectState after = readSnapshot(afterSnapshot);
        return new StateDiff(fromVersion, toVersion, changedPaths(before, after), before, after);
    }

    private ProjectState initialState(String learnerId, String projectId) {
        LearnerProfile profile = profileService.getDefault();
        return new ProjectState(
                learnerId,
                projectId,
                new ProjectState.Goal(profile.getGoal(), "Agent 工程师", "Java 后端工程师"),
                profile.getStage(),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                new ProjectState.Preferences(profile.getPreferences(), "完整可运行", "zh-CN"),
                0,
                Instant.now(),
                List.of("profile:default")
        );
    }

    private void validatePatch(StatePatch patch, ProjectState current) {
        if (patch == null || patch.ops() == null || patch.ops().isEmpty()) {
            throw new IllegalArgumentException("state patch must contain at least one op");
        }
        if (patch.baseVersion() == null || patch.baseVersion() != current.version()) {
            throw new VersionConflictException("state version conflict: expected " + current.version()
                    + ", got " + patch.baseVersion());
        }

        for (StateOp op : patch.ops()) {
            if (op == null) {
                throw new IllegalArgumentException("state op must not be null");
            }
            String normalizedOp = op.op() == null ? "" : op.op().trim().toUpperCase();
            if (!OPS.contains(normalizedOp)) {
                throw new IllegalArgumentException("unsupported state op: " + op.op());
            }
            if (op.path() == null || !ALLOWED_PATHS.contains(op.path())) {
                throw new IllegalArgumentException("state path is not allowed: " + op.path());
            }
            if (op.evidence() == null || op.evidence().isEmpty()
                    || op.evidence().stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("every state op must include non-blank evidence");
            }
            if (op.confidence() != null && (op.confidence() < 0.0 || op.confidence() > 1.0)) {
                throw new IllegalArgumentException("confidence must be between 0 and 1");
            }
            if (("ADD".equals(normalizedOp) || "REMOVE".equals(normalizedOp))
                    && !LIST_PATHS.contains(op.path())) {
                throw new IllegalArgumentException(normalizedOp + " is only supported for list paths");
            }
            if ("RESOLVE".equals(normalizedOp)
                    && !"currentBlocker".equals(op.path())
                    && !"openQuestions".equals(op.path())) {
                throw new IllegalArgumentException("RESOLVE is only supported for currentBlocker or openQuestions");
            }
            if (("SET".equals(normalizedOp) || "ADD".equals(normalizedOp)) && op.value() == null) {
                throw new IllegalArgumentException(normalizedOp + " requires a value");
            }
        }
    }

    private void validateState(ProjectState state) {
        if (state.learnerId() == null || state.learnerId().isBlank()
                || state.projectId() == null || state.projectId().isBlank()) {
            throw new IllegalArgumentException("state learnerId/projectId must not be blank");
        }
        if (state.stage() == null || state.stage().isBlank()) {
            throw new IllegalArgumentException("state stage must not be blank");
        }
        if (state.goal() == null) {
            throw new IllegalArgumentException("state goal must not be null");
        }
        if (state.preferences() == null) {
            throw new IllegalArgumentException("state preferences must not be null");
        }
    }

    private void applyOp(ObjectNode root, StateOp op) {
        String normalizedOp = op.op().trim().toUpperCase();
        String path = op.path();

        switch (normalizedOp) {
            case "SET" -> root.set(path, op.value() == null ? NullNode.getInstance() : op.value());
            case "ADD" -> {
                ArrayNode array = requireArray(root, path);
                array.add(op.value());
            }
            case "REMOVE" -> {
                ArrayNode array = requireArray(root, path);
                String key = textValue(op.value());
                boolean removed = removeMatching(array, key);
                if (!removed) {
                    throw new IllegalArgumentException("state item not found for REMOVE: " + key);
                }
            }
            case "RESOLVE" -> resolve(root, op);
            default -> throw new IllegalArgumentException("unsupported state op: " + op.op());
        }
    }

    private void resolve(ObjectNode root, StateOp op) {
        if ("currentBlocker".equals(op.path())) {
            root.set("currentBlocker", NullNode.getInstance());
            return;
        }

        String question = textValue(op.value());
        ArrayNode questions = requireArray(root, "openQuestions");
        for (JsonNode item : questions) {
            if (item.isObject() && question.equals(item.path("question").asText())) {
                ((ObjectNode) item).put("status", "RESOLVED");
                return;
            }
        }
        throw new IllegalArgumentException("open question not found for RESOLVE: " + question);
    }

    private ArrayNode requireArray(ObjectNode root, String path) {
        JsonNode node = root.get(path);
        if (node instanceof ArrayNode array) {
            return array;
        }
        if (node == null || node.isNull()) {
            ArrayNode array = objectMapper.createArrayNode();
            root.set(path, array);
            return array;
        }
        throw new IllegalArgumentException("state path is not an array: " + path);
    }

    private boolean removeMatching(ArrayNode array, String key) {
        for (int index = 0; index < array.size(); index++) {
            JsonNode item = array.get(index);
            if (item.isObject()) {
                String topic = item.path("topic").asText("");
                String question = item.path("question").asText("");
                if (key.equals(topic) || key.equals(question)) {
                    array.remove(index);
                    return true;
                }
            }
        }
        return false;
    }

    private String textValue(JsonNode value) {
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("this state op requires a text value");
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isObject()) {
            String topic = value.path("topic").asText("");
            if (!topic.isBlank()) {
                return topic;
            }
            String question = value.path("question").asText("");
            if (!question.isBlank()) {
                return question;
            }
        }
        throw new IllegalArgumentException("this state op requires a text value");
    }

    private List<String> mergeEvidence(List<String> existing, List<StateOp> ops) {
        Set<String> merged = new LinkedHashSet<>(existing == null ? List.of() : existing);
        for (StateOp op : ops) {
            merged.addAll(op.evidence());
        }
        return List.copyOf(merged);
    }

    private List<String> changedPaths(ProjectState before, ProjectState after) {
        JsonNode beforeNode = objectMapper.valueToTree(before);
        JsonNode afterNode = objectMapper.valueToTree(after);
        Set<String> fields = new LinkedHashSet<>();
        Iterator<String> beforeFields = beforeNode.fieldNames();
        while (beforeFields.hasNext()) {
            fields.add(beforeFields.next());
        }
        Iterator<String> afterFields = afterNode.fieldNames();
        while (afterFields.hasNext()) {
            fields.add(afterFields.next());
        }

        List<String> changed = new ArrayList<>();
        for (String field : fields) {
            if (!beforeNode.path(field).equals(afterNode.path(field))) {
                changed.add(field);
            }
        }
        return changed;
    }

    private void saveSnapshot(ProjectState state) {
        try {
            snapshots.save(new StateSnapshot(
                    state.learnerId(),
                    state.projectId(),
                    state.version(),
                    objectMapper.writeValueAsString(state),
                    summarize(state),
                    LocalDateTime.now()
            ));
        } catch (Exception ex) {
            throw new IllegalStateException("unable to persist state snapshot", ex);
        }
    }

    private void saveEvent(ProjectState from,
                           ProjectState to,
                           StatePatch patch,
                           List<String> evidence,
                           String turnId,
                           boolean correction) {
        try {
            events.save(new StateEvent(
                    to.learnerId(),
                    to.projectId(),
                    from.version(),
                    to.version(),
                    turnId,
                    objectMapper.writeValueAsString(patch),
                    objectMapper.writeValueAsString(evidence),
                    correction ? "CORRECTED" : "APPLIED",
                    LocalDateTime.now()
            ));
        } catch (Exception ex) {
            throw new IllegalStateException("unable to persist state event", ex);
        }
    }

    private ProjectState readSnapshot(StateSnapshot snapshot) {
        try {
            return objectMapper.readValue(snapshot.getStateJson(), ProjectState.class);
        } catch (Exception ex) {
            throw new IllegalStateException("unable to parse state snapshot " + snapshot.getVersion(), ex);
        }
    }

    private String summarize(ProjectState state) {
        String blocker = state.currentBlocker() == null ? "无" : state.currentBlocker().topic();
        String next = state.nextActions().isEmpty()
                ? "暂无"
                : state.nextActions().get(0).action();
        String summary = "阶段：" + state.stage()
                + "；卡点：" + blocker
                + "；下一步：" + next
                + "；掌握 " + state.mastered().size() + " 项";
        return summary.length() <= 1000 ? summary : summary.substring(0, 1000);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "default" : value.trim();
    }
}