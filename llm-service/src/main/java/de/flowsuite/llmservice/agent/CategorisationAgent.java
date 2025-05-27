package de.flowsuite.llmservice.agent;

import de.flowsuite.llmservice.common.ModelResponse;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModelName;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class CategorisationAgent {

    private static final Logger LOG = LoggerFactory.getLogger(CategorisationAgent.class);
    private static final OpenAiChatModelName MODEL_NAME = OpenAiChatModelName.O4_MINI;
    private static final double TEMPERATURE = 1;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_RETRIES = 3;

    // spotless:off
    private static final String DEFAULT_SYSTEM_PROMPT =
        """
        # Identity
        You are a highly accurate message categorisation assistant.
    
        # Objective
        Categorise each message into **one and only one** of the predefined categories listed below.
    
        # Mandatory Rules (Strict Compliance Required):
        1. You MUST respond with ONLY the exact name of one valid category from the list below — NO explanations, NO additional text.
        2. NEVER invent or modify category names.
        3. If a message could belong to multiple categories, choose the ONE that best reflects the **main subject**.
        4. If a message does not clearly fit any category, respond with "Default".
    
        # Output Format
        Your response must match **exactly** one of the categories. Do NOT add punctuation, notes, or any other content.
    
        # Allowed Categories:
        {{categories}}
        """;
    // spotless:on

    private final CategorisationAssistant assistant;

    public CategorisationAgent(String openaiApiKey, String formattedCategories, boolean debug) {
        String systemPrompt = DEFAULT_SYSTEM_PROMPT.replace("{{categories}}", formattedCategories);

        LOG.debug("System prompt: {}", systemPrompt);

        ChatModel model =
                OpenAiChatModel.builder()
                        .apiKey(openaiApiKey)
                        .modelName(MODEL_NAME)
                        .temperature(TEMPERATURE)
                        .timeout(TIMEOUT)
                        .maxRetries(MAX_RETRIES)
                        .logRequests(debug)
                        .logResponses(debug)
                        .build();

        this.assistant =
                AiServices.builder(CategorisationAssistant.class)
                        .chatModel(model)
                        .chatMemoryProvider(userId -> MessageWindowChatMemory.withMaxMessages(10))
                        .systemMessageProvider(userId -> systemPrompt)
                        .build();
    }

    public ModelResponse categorise(long userId, String message) {
        LOG.info("Categorising message for user {}", userId);

        Response<AiMessage> aiResponse = assistant.categorise(userId, message);

        return new ModelResponse(
                aiResponse.content().text(),
                MODEL_NAME.toString(),
                aiResponse.tokenUsage().inputTokenCount(),
                aiResponse.tokenUsage().outputTokenCount(),
                aiResponse.tokenUsage().totalTokenCount());
    }

    interface CategorisationAssistant {

        @UserMessage("Categorise the following message. Respond ONLY with one valid category name from the list you were given. Do NOT explain or invent anything. Message: {{message}}")
        Response<AiMessage> categorise(@MemoryId long userId, @V("message") String message);
    }
}
