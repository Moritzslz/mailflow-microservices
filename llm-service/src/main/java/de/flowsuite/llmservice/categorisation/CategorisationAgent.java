package de.flowsuite.llmservice.categorisation;

import de.flowsuite.mailflow.common.entity.MessageCategory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModelName;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.util.List;

class CategorisationAgent {

    private static final Logger LOG = LoggerFactory.getLogger(CategorisationAgent.class);
    private static final OpenAiChatModelName MODEL_NAME = OpenAiChatModelName.GPT_4_O_MINI;
    private static final double TEMPERATURE = 0.0;
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final int MAX_RETRIES = 3;
    private static String systemMessage =
            """
Your are an expert in categorising messages. Please categorise the message in the following categories:
\n
 %s
\n
 Message:
\n {{message}}
""";
    private static String userMessage =
            """
            Please categorise the message in the following categories:
            \n
             %s
            \n
             Message:
            \n {{message}}
            """;

    private final CategorisationAssistant assistant;

    CategorisationAgent(
            @Value("${langchain.debug}") boolean debug,
            String openaiApiKey,
            List<MessageCategory> messageCategories) {
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

        systemMessage = String.format(systemMessage, messageCategories);
        userMessage = String.format(userMessage, messageCategories);

        LOG.debug("System message: {}", systemMessage);
        LOG.debug("User message: {}", userMessage);

        this.assistant =
                AiServices.builder(CategorisationAssistant.class)
                        .chatModel(model)
                        .systemMessageProvider(chatMemoryId -> systemMessage)
                        .build();
    }

    public CategorisationResult categorise(String message) {
        LOG.info("Categorising message...");

        Response<AiMessage> aiResponse = assistant.categorise(userMessage, message);

        return new CategorisationResult(
                aiResponse.content().text(),
                MODEL_NAME.toString(),
                aiResponse.tokenUsage().inputTokenCount(),
                aiResponse.tokenUsage().outputTokenCount(),
                aiResponse.tokenUsage().totalTokenCount());
    }

    interface CategorisationAssistant {
        Response<AiMessage> categorise(
                @UserMessage String userMessage, @V("message") String message);
    }

    record CategorisationResult(
            String text,
            String modelName,
            long inputTokens,
            long outputTokens,
            long totalTokenUsage) {}
}
