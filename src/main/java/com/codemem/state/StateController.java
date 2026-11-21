package com.codemem.state;

import com.codemem.memory.longterm.MemoryItemService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/state")
public class StateController {

    private final StateService stateService;
    private final MemoryItemService memoryItemService;

    public StateController(StateService stateService, MemoryItemService memoryItemService) {
        this.stateService = stateService;
        this.memoryItemService = memoryItemService;
    }

    @GetMapping
    public ProjectState current(@RequestParam(defaultValue = "default") String learnerId,
                                @RequestParam(defaultValue = "default") String projectId) {
        return stateService.getOrCreate(learnerId, projectId);
    }

    @GetMapping("/history")
    public List<StateHistoryView> history(@RequestParam(defaultValue = "default") String learnerId,
                                          @RequestParam(defaultValue = "default") String projectId) {
        return stateService.history(learnerId, projectId);
    }

    @GetMapping("/diff")
    public StateDiff diff(@RequestParam(defaultValue = "default") String learnerId,
                          @RequestParam(defaultValue = "default") String projectId,
                          @RequestParam int from,
                          @RequestParam int to) {
        return stateService.diff(learnerId, projectId, from, to);
    }

    @PostMapping("/patch")
    public ProjectState patch(@RequestParam(defaultValue = "default") String learnerId,
                              @RequestParam(defaultValue = "default") String projectId,
                              @Valid @RequestBody StatePatch patch) {
        return stateService.applyPatch(learnerId, projectId, patch, null, false);
    }

    @PostMapping("/correct")
    public ProjectState correct(@Valid @RequestBody CorrectionRequest request) {
        ProjectState state = stateService.applyPatch(
                request.learnerId(),
                request.projectId(),
                request.patch(),
                null,
                true
        );
        recordCorrectionMemory(request, state);
        return state;
    }

    /**
     * 人工纠错也要写入长期记忆：同主题旧记忆会被置为 SUPERSEDED，新记忆立即可检索。
     */
    private void recordCorrectionMemory(CorrectionRequest request, ProjectState state) {
        for (StateOp op : request.patch().ops()) {
            JsonNode value = op.value();
            String content = switch (op.path()) {
                case "currentBlocker", "weakPoints" -> "卡点："
                        + value.path("topic").asText("")
                        + (value.hasNonNull("symptom") ? "：" + value.path("symptom").asText("") : "");
                case "nextActions" -> "计划：" + value.path("action").asText("");
                case "mastered" -> "掌握：" + value.path("topic").asText("");
                case "stage" -> "阶段：" + value.asText("");
                default -> null;
            };
            if (content == null || content.isBlank()) {
                continue;
            }
            memoryItemService.recordCorrection(
                    request.learnerId(),
                    request.projectId(),
                    MemoryItemService.topicOf(content),
                    content,
                    "manual:v" + state.version()
            );
        }
    }

}