package de.flowsuite.llmservice.agent;

import de.flowsuite.mailflow.common.dto.LlmResponse;
import de.flowsuite.mailflow.common.entity.Customer;
import de.flowsuite.mailflow.common.util.AesUtil;

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

public class GenerationAgent {

    private static final Logger LOG = LoggerFactory.getLogger(GenerationAgent.class);
    private static final OpenAiChatModelName MODEL_NAME = OpenAiChatModelName.GPT_4_1_MINI;
    private static final double TEMPERATURE = 0.1;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_RETRIES = 3;

    // spotless:off
    private static final String DEFAULT_SYSTEM_PROMPT =
        """
        # Identity
        You are a helpful and professional email assistant.

        # Instructions
        Your task is to generate high-quality, context-aware replies to customer emails based on retrieved documents and background knowledge.

        # Guidelines:
        1. Always use the information provided in the retrieved context to support your reply. Do not make up facts.
        2. If relevant information is not found in the context, reply honestly and offer to follow up or escalate as needed.
           **If more information is required, ask clarifying questions or trigger relevant functions** to retrieve or act upon additional data.
        3. Write in a polite, helpful, and professional tone appropriate for business communication.
        4. Use clear and concise language. Avoid jargon unless it was used in the incoming message or is appropriate for the recipient.
        5. If a customer name is found in the incoming message or thread, include a matching greeting at the beginning of the reply.
        6. Do NOT include a closing phrase, signature, or contact information.
        7. Do not mention that you are using retrieved documents or AI. The response should read as if written directly by a human assistant.
        8. **Write the reply in German if the customer email is in German. If the email is in any other language, reply in English.**

        ### Input:
        - Incoming customer email
        - Retrieved context documents (from internal sources, knowledge base, etc.)
        - Email thread (may contain sender name)

        ### Output:
        - A complete, well-structured HTML email **body** containing only the message text.

        ## Formatting Rules:
        - **Respond in valid HTML. Output only HTML — no plain text.**
        - If a name is detected in the email thread, begin with a greeting using that name (e.g., <p>Dear Julia,</p>).
        - Do NOT include a closing phrase (e.g., “Best regards”, names, or titles).
        - Do NOT include an email footer or contact information.
        - Use proper HTML tags such as <p>, <ul>, <strong>, <br> where appropriate.
        - Ensure readability and professional tone in the formatting.

        ## Function Calling:
        - If the customer’s query requires external action (e.g., retrieving real-time information, verifying data, getting documents), **invoke the appropriate function** and ask the user for any required parameters.
        - If the necessary context is unavailable, **ask clarifying questions** to better understand the issue and guide the customer towards providing the needed information.

        **Maintain professionalism, empathy, and clarity at all times. Output must be valid, styled HTML with no plain text fallback.**

        ## Examples:

        ---
        Example 1 (English, HTML):

        Customer email:
        I’m having trouble updating my billing details. I keep getting an error saying “payment method invalid.”

        Context:
        The billing portal only accepts Visa, Mastercard, and corporate AMEX cards. Some debit cards may be rejected by our payment processor.

        Expected Reply:
        <p>Dear Alex,</p>
        <p>Please make sure you're using a supported payment method — we currently accept <strong>Visa</strong>, <strong>Mastercard</strong>, and <strong>AMEX</strong>. If you're using a debit card, try switching to a credit card or contact your bank to confirm support.</p>

        ---
        Example 2 (German, HTML):

        Customer Email:
        Ich finde die Option zum Passwort Zurücksetzen nicht. Können Sie mir bitte helfen?

        Context:
        Das Passwort kann unter "Login > Passwort vergessen" zurückgesetzt werden. Nutzer müssen zuerst ihre E-Mail-Adresse bestätigen.

        Expected Reply:
        <p>Liebe Julia,</p>
        <p>Sie können Ihr Passwort über die Funktion <strong>„Passwort vergessen“</strong> auf der Login-Seite zurücksetzen. Bitte stellen Sie sicher, dass Sie Ihre E-Mail-Adresse korrekt eingegeben und bestätigt haben.</p>
        """;


    private static final String DEFAULT_USER_MESSAGE =
        """
        # Instructions:
        -------------
        1. Carefully review the retrieved context and the customer email thread below.
        2. Your task is to generate a professional, helpful, and concise reply.
            - Use information from the retrieved context when relevant.
            - Be polite, and avoid repeating full context text verbatim.
        
        3. If the retrieved context lacks information necessary to address the customer’s question:
            - If the function call still does not fully resolve the issue, ask a clear and polite clarifying question to gather more details from the customer.
        
        4. Do NOT invent information or answer questions without sufficient evidence from the context
        
        ## Retrieved Context:
        ------------------
        {{context}}
        
        ## Customer Email Thread:
        ----------------------
        {{messageThread}}
        """;

    private static final String DEFAULT_USER_MESSAGE_FUNCTION_CALL =
        """
        # Instructions:
        -------------
        1. Carefully review the retrieved context and the customer email thread below.
        2. Your task is to generate a professional, helpful, and concise reply.
           - Use information from the retrieved context when relevant.
           - Be polite, and avoid repeating full context text verbatim.
        
        3. If the retrieved context lacks information necessary to address the customer’s question:
           - You MUST call one of the defined functions that could help answer the question.
           - If the function call still does not fully resolve the issue, ask a clear and polite clarifying question to gather more details from the customer.
        
        4. You are required to call **one and only one** of the available functions below. Choose the one that best fits the customer's need based on the conversation and context.
        5. Do NOT invent information or answer questions without sufficient evidence from the context or function call result.
    
        ## Functions:
        ----------
        {{functions}}
    
        ## Retrieved Context:
        ------------------
        {{context}}
    
        ## Customer Email Thread:
        ----------------------
        {{messageThread}}
        """;

    // spotless:on

    private final GenerationAssistant assistant;

    public GenerationAgent(Customer customer, boolean debug) {
        String systemPrompt;
        if (customer.getSystemPrompt() != null && !customer.getSystemPrompt().isBlank()) {
            systemPrompt = customer.getSystemPrompt();
        } else {
            systemPrompt = DEFAULT_SYSTEM_PROMPT;
        }

        ChatModel model =
                OpenAiChatModel.builder()
                        .apiKey(AesUtil.decrypt(customer.getOpenaiApiKey()))
                        .modelName(MODEL_NAME)
                        .temperature(TEMPERATURE)
                        .timeout(TIMEOUT)
                        .maxRetries(MAX_RETRIES)
                        .logRequests(debug)
                        .logResponses(debug)
                        .build();

        this.assistant =
                AiServices.builder(GenerationAssistant.class)
                        .chatModel(model)
                        .chatMemoryProvider(userId -> MessageWindowChatMemory.withMaxMessages(10))
                        .systemMessageProvider(userId -> systemPrompt)
                        .build();
    }

    public LlmResponse generateContextualReply(
            long userId, String userMessage, String ragContext, String messageThread) {
        LOG.info("Generating reply for user {}", userId);

        if (userMessage == null || userMessage.isBlank()) {
            userMessage = DEFAULT_USER_MESSAGE;
        }

        Response<AiMessage> aiResponse =
                assistant.generateReply(userId, userMessage, ragContext, messageThread);

        return new LlmResponse(
                aiResponse.content().text(),
                MODEL_NAME.toString(),
                aiResponse.tokenUsage().inputTokenCount(),
                aiResponse.tokenUsage().outputTokenCount(),
                aiResponse.tokenUsage().totalTokenCount());
    }

    // TODO
    public LlmResponse generateContextualReplyWithFunctionCall(
            long userId,
            String userMessage,
            String context,
            String availableFunctions,
            String messageThread) {
        LOG.info("Generating reply using function calling for user {}", userId);

        if (userMessage == null || userMessage.isBlank()) {
            userMessage =
                    DEFAULT_USER_MESSAGE_FUNCTION_CALL.replace("{{functions}}", availableFunctions);
        } else {
            userMessage = userMessage.replace("{{functions}}", availableFunctions);
        }

        Response<AiMessage> aiResponse =
                assistant.generateReply(userId, userMessage, context, messageThread);

        return new LlmResponse(
                aiResponse.content().text(),
                MODEL_NAME.toString(),
                aiResponse.tokenUsage().inputTokenCount(),
                aiResponse.tokenUsage().outputTokenCount(),
                aiResponse.tokenUsage().totalTokenCount());
    }

    interface GenerationAssistant {
        Response<AiMessage> generateReply(
                @MemoryId long userId,
                @UserMessage String userMessage,
                @V("context") String context,
                @V("messageThread") String messageThread);
    }
}
