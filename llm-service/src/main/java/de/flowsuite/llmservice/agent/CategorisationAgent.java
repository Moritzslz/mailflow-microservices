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
    private static final OpenAiChatModelName MODEL_NAME = OpenAiChatModelName.GPT_4_1_MINI;
    private static final double TEMPERATURE = 0.1;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_RETRIES = 3;

    // spotless:off
    private static final String DEFAULT_SYSTEM_PROMPT =
        """
        You are a highly accurate message categorisation assistant.

        Your task is to categorise each message into one of the predefined categories listed below.
        Always choose the most appropriate single category based on the meaning and context of the message.

        Follow these rules:
        1. Respond ONLY with the name of the category (no extra text).
        2. If the message fits more than one category, choose the one that best matches the core topic.
        3. If it does not clearly belong to any category, choose "Default".

        Categories:
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

        @UserMessage("Please categorise the following message: {{message}}")
        Response<AiMessage> categorise(@MemoryId long userId, @V("message") String message);
    }
}
