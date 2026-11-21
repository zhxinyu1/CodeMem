package com.codemem.state;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CorrectionRequest(
        String learnerId,
        String projectId,
        @NotNull(message = "patch 不能为空")
        @Valid
        StatePatch patch
) {
}