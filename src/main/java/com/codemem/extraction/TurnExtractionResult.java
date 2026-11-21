package com.codemem.extraction;

import com.codemem.state.StateOp;

import java.util.List;

public record TurnExtractionResult(
        String summary,
        List<String> memoryCandidates,
        List<StateOp> ops,
        String reason
) {
}