package de.flowsuite.llmservice.categorisation;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModelName;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

class CategorisationAgent {

    private static final Logger LOG = LoggerFactory.getLogger(CategorisationAgent.class);
    private static final OpenAiChatModelName MODEL_NAME =
            OpenAiChatModelName.GPT_4_1_MINI; // Todo use 4_O_MINI for generation
    private static final double TEMPERATURE = 0.1;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_RETRIES = 3;

    private final CategorisationAssistant assistant;

    CategorisationAgent(String openaiApiKey, boolean debug) {
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
                        .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                        .build();
    }

    public CategorisationResult categorise(String categories, String message) {
        LOG.info("Categorising message...");

        Response<AiMessage> aiResponse = assistant.categorise(categories, message);

        return new CategorisationResult(
                aiResponse.content().text(),
                MODEL_NAME.toString(),
                aiResponse.tokenUsage().inputTokenCount(),
                aiResponse.tokenUsage().outputTokenCount(),
                aiResponse.tokenUsage().totalTokenCount());
    }

    interface CategorisationAssistant {

        @SystemMessage(
                """
You are a highly accurate message categorisation assistant.

Your task is to categorise each message into one of the predefined categories listed below.
Always choose the most appropriate single category based on the meaning and context of the message.

Categories:
{{categories}}

Follow these rules:
1. Respond ONLY with the name of the category (no extra text).
2. If the message fits more than one category, choose the one that best matches the core topic.
3. If it does not clearly belong to any category, choose "Default".
""")
        @UserMessage(
                """
                Please categorise the following message:

                {{message}}
                """)
        Response<AiMessage> categorise(
                @V("categories") String categories, @V("message") String message);
    }

    record CategorisationResult(
            String text, String modelName, long inputTokens, long outputTokens, long totalTokens) {}
}
