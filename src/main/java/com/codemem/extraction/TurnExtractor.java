package com.codemem.extraction;

import com.codemem.outbox.TurnCompletedPayload;

public interface TurnExtractor {

    TurnExtractionResult extract(TurnCompletedPayload payload);
}