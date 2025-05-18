package de.flowsuite.llmservice.common;

public record ModelResponse(
        String text, String modelName, long inputTokens, long outputTokens, long totalTokens) {}
