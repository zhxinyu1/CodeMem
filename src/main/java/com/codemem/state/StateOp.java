package com.codemem.state;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record StateOp(
        @NotBlank(message = "op 不能为空")
        String op,
        @NotBlank(message = "path 不能为空")
        String path,
        JsonNode value,
        @DecimalMin(value = "0.0", message = "confidence 不能小于 0")
        @DecimalMax(value = "1.0", message = "confidence 不能大于 1")
        Double confidence,
        @NotEmpty(message = "每条状态变更必须带证据")
        List<String> evidence
) {
}