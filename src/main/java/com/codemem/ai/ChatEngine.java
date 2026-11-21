package com.codemem.ai;

import reactor.core.publisher.Flux;

public interface ChatEngine {

    Flux<String> stream(ChatModelRequest request);

    String complete(ChatModelRequest request);
}