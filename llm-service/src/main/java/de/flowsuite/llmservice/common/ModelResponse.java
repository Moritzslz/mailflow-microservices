package de.flowsuite.llmservice.common;

public record ModelResponse(
        String text, String modelName, int inputTokens, int outputTokens, int totalTokens) {}
