package de.flowsuite.llmservice.service;

import static de.flowsuite.mailflow.common.util.Util.BERLIN_ZONE;

import de.flowsuite.llmservice.agent.CategorisationAgent;
import de.flowsuite.llmservice.agent.GenerationAgent;
import de.flowsuite.llmservice.common.ModelResponse;
import de.flowsuite.llmservice.exception.InvalidHtmlBodyException;
import de.flowsuite.llmservice.util.LlmServiceUtil;
import de.flowsuite.mailflow.common.client.ApiClient;
import de.flowsuite.mailflow.common.dto.CategorisationResponse;
import de.flowsuite.mailflow.common.dto.CreateMessageLogEntryRequest;
import de.flowsuite.mailflow.common.entity.Customer;
import de.flowsuite.mailflow.common.entity.MessageCategory;
import de.flowsuite.mailflow.common.entity.MessageLogEntry;
import de.flowsuite.mailflow.common.entity.User;
import de.flowsuite.mailflow.common.exception.IdConflictException;
import de.flowsuite.mailflow.common.util.AesUtil;
import de.flowsuite.shared.exception.ExceptionManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.*;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LlmService {

    private static final Logger LOG = LoggerFactory.getLogger(LlmService.class);

    private static final ConcurrentHashMap<Long, Customer> customers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, CategorisationAgent> categorisationAgents =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, GenerationAgent> generationsAgents =
            new ConcurrentHashMap<>();
    private static final String RESPONSE_RATING_URI = "/response-ratings";

    private final boolean debug;
    private final String mailflowFrontendUrl;
    private final ApiClient apiClient;
    private final ExceptionManager exceptionManager;

    public LlmService(
            @Value("${langchain.debug}") boolean debug,
            @Value("${mailflow.frontend.url}") String mailflowFrontendUrl,
            ApiClient apiClient,
            ExceptionManager exceptionManager) {
        this.debug = debug;
        this.mailflowFrontendUrl = mailflowFrontendUrl;
        this.apiClient = apiClient;
        this.exceptionManager = exceptionManager;
    }

    public Optional<CategorisationResponse> categoriseMessage(
            User user, String message, List<MessageCategory> categories) {
        LOG.info("Categorising message for user {}", user.getId());

        Customer customer = getOrFetchCustomer(user);

        LOG.debug("Categories: {}", categories);

        String formattedCategories = LlmServiceUtil.formatCategories(categories);

        LOG.debug("Formatted categories: {}", formattedCategories);

        CategorisationAgent agent = getOrCreateCategorisationAgent(customer, formattedCategories);
        ModelResponse response = agent.categorise(user.getId(), message);

        LOG.debug("Categorisation response: {}", response);

        String category = response.text();

        for (MessageCategory messageCategory : categories) {
            if (messageCategory.getCategory().equalsIgnoreCase(category)) {
                return Optional.of(
                        new CategorisationResponse(
                                messageCategory,
                                response.modelName(),
                                response.inputTokens(),
                                response.outputTokens(),
                                response.totalTokens()));
            }
        }

        return Optional.empty();
    }

    public Optional<String> generateReply(
            User user,
            String messageThread,
            String fromEmailAddress,
            String subject,
            ZonedDateTime receivedAt,
            CategorisationResponse categorisationResponse)
            throws URISyntaxException, MalformedURLException {
        LOG.info("Generating reply message for user {}", user.getId());

        Customer customer = getOrFetchCustomer(user);

        // Todo call rag service

        String context = "No context";

        GenerationAgent generationAgent = getOrCreateGenerationAgent(customer);

        MessageCategory messageCategory = categorisationResponse.category();

        ModelResponse generationResponse;
        if (!messageCategory.getFunctionCall()) {
            generationResponse =
                    generationAgent.generateContextualReply(
                            user.getId(), null, context, messageThread);
        } else {
            generationResponse =
                    generationAgent.generateContextualReplyWithFunctionCall(
                            user.getId(), null, context, "none", messageThread);
        }

        LOG.debug("Reply response: {}", generationResponse);

        try {
            LlmServiceUtil.validateHtmlBody(generationResponse.text());
        } catch (InvalidHtmlBodyException e) {
            exceptionManager.handleException(e);
            return Optional.empty();
        }

        ZonedDateTime now = ZonedDateTime.now(BERLIN_ZONE);
        int processingTimeInSeconds = (int) Duration.between(receivedAt, now).getSeconds();

        CreateMessageLogEntryRequest request =
                new CreateMessageLogEntryRequest(
                        user.getId(),
                        user.getCustomerId(),
                        true,
                        messageCategory.getFunctionCall(),
                        messageCategory.getCategory(),
                        null, // TODO
                        fromEmailAddress,
                        subject,
                        receivedAt,
                        now,
                        processingTimeInSeconds,
                        categorisationResponse.llmUsed(),
                        categorisationResponse.inputTokens(),
                        categorisationResponse.outputTokens(),
                        categorisationResponse.totalTokens(),
                        generationResponse.modelName(),
                        generationResponse.inputTokens(),
                        generationResponse.outputTokens(),
                        generationResponse.totalTokens());

        MessageLogEntry messageLogEntry = apiClient.createMessageLogEntry(request);

        URL url = null;
        if (user.getSettings().isResponseRatingEnabled()) {
            String baseUrl = mailflowFrontendUrl + RESPONSE_RATING_URI;
            String query = "token=" + messageLogEntry.getToken();
            URI uri = new URI(baseUrl + "?" + query);
            url = uri.toURL();
        }

        return Optional.of(
                LlmServiceUtil.createHtmlMessage(generationResponse.text(), user, customer, url));
    }

    public void onCustomerUpdated(long customerId, Customer customer) {
        LOG.debug("Updating customer {}", customerId);

        if (!customer.getId().equals(customerId)) {
            throw new IdConflictException();
        }

        customers.put(customerId, customer);
    }

    private Customer getOrFetchCustomer(User user) {
        return customers.computeIfAbsent(
                user.getCustomerId(),
                id -> apiClient.getCustomer(user.getCustomerId())); // Blocking request
    }

    private CategorisationAgent getOrCreateCategorisationAgent(
            Customer customer, String formattedCategories) {
        return categorisationAgents.computeIfAbsent(
                customer.getId(),
                id ->
                        new CategorisationAgent(
                                AesUtil.decrypt(customer.getOpenaiApiKey()),
                                formattedCategories,
                                debug));
    }

    private GenerationAgent getOrCreateGenerationAgent(Customer customer) {
        return generationsAgents.computeIfAbsent(
                customer.getId(), id -> new GenerationAgent(customer, debug));
    }
}
