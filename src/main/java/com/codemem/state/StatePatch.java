package com.codemem.state;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record StatePatch(
        @NotNull(message = "baseVersion 不能为空")
        Integer baseVersion,
        @NotEmpty(message = "ops 不能为空")
        List<@Valid StateOp> ops,
        String reason
) {
}