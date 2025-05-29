package de.flowsuite.llmservice.service;

import de.flowsuite.llmservice.agent.CategorisationAgent;
import de.flowsuite.llmservice.agent.GenerationAgent;
import de.flowsuite.llmservice.exception.InvalidHtmlBodyException;
import de.flowsuite.llmservice.util.LlmServiceUtil;
import de.flowsuite.mailflow.common.client.ApiClient;
import de.flowsuite.mailflow.common.client.RagServiceClient;
import de.flowsuite.mailflow.common.dto.*;
import de.flowsuite.mailflow.common.entity.Customer;
import de.flowsuite.mailflow.common.entity.MessageCategory;
import de.flowsuite.mailflow.common.entity.MessageLogEntry;
import de.flowsuite.mailflow.common.entity.User;
import de.flowsuite.mailflow.common.exception.IdConflictException;
import de.flowsuite.mailflow.common.util.AesUtil;
import de.flowsuite.mailflow.common.util.Util;
import de.flowsuite.shared.exception.ExceptionManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.*;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LlmService {

    private static final Logger LOG = LoggerFactory.getLogger(LlmService.class);

    private static final ConcurrentHashMap<Long, Customer> customers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, CategorisationAgent> categorisationAgentsByCustomer =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, GenerationAgent> generationsAgentsByCustomer =
            new ConcurrentHashMap<>();
    private static final String RESPONSE_RATING_URI = "/response-ratings";

    private final boolean debug;
    private final String mailflowFrontendUrl;
    private final ApiClient apiClient;
    private final RagServiceClient ragServiceClient;
    private final ExceptionManager exceptionManager;

    public LlmService(
            @Value("${langchain.debug}") boolean debug,
            @Value("${mailflow.frontend.url}") String mailflowFrontendUrl,
            ApiClient apiClient,
            RagServiceClient ragServiceClient,
            ExceptionManager exceptionManager) {
        this.debug = debug;
        this.mailflowFrontendUrl = mailflowFrontendUrl;
        this.apiClient = apiClient;
        this.ragServiceClient = ragServiceClient;
        this.exceptionManager = exceptionManager;
    }

    public Optional<CategorisationResponse> categoriseMessage(
            User user, String message, List<MessageCategory> categories) {
        LOG.info("Categorising message for user {}", user.getId());

        Customer customer = getOrFetchCustomer(user);
        CategorisationAgent agent = getOrCreateCategorisationAgent(customer);

        String formattedCategories = LlmServiceUtil.formatCategories(categories);
        LOG.debug("Formatted categories:\n{}", formattedCategories);

        int maxAttempts = 2;
        Optional<CategorisationResponse> category = Optional.empty();

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            LlmResponse response = agent.categorise(user.getId(), formattedCategories, message);
            LOG.debug("Attempt {} - LLM Response: {}", attempt + 1, response);

            category = LlmServiceUtil.validateAndMapCategory(response, categories);

            if (category.isPresent()) {
                return category;
            }
        }

        LOG.warn(
                "Failed to categorise message after {} attempts for user {}",
                maxAttempts,
                user.getId());
        return category;
    }

    public Optional<String> generateReply(
            User user,
            List<ThreadMessage> messageThread,
            String fromEmailAddress,
            String subject,
            ZonedDateTime receivedAt,
            CategorisationResponse categorisationResponse)
            throws URISyntaxException, MalformedURLException {
        LOG.info("Generating reply message for user {}", user.getId());

        Customer customer = getOrFetchCustomer(user);
        GenerationAgent generationAgent = getOrCreateGenerationAgent(customer);
        MessageCategory messageCategory = categorisationResponse.messageCategory();

        String ragContext = fetchRagContext(customer.getId(), user.getId(), messageThread);
        String threadBody = Util.buildThreadBody(messageThread, false, null);

        LlmResponse generationResponse;
        if (!messageCategory.getFunctionCall()) {
            if (ragContext == null || ragContext.isBlank()) {
                return Optional.empty();
            }
            generationResponse =
                    generationAgent.generateContextualReply(
                            user.getId(), customer.getMessagePrompt(), ragContext, threadBody);
        } else {
            if (ragContext == null || ragContext.isBlank()) {
                ragContext = "No relevant context found";
            }
            String availableFunctions = "No functions defined"; // TODO
            generationResponse =
                    generationAgent.generateContextualReplyWithFunctionCall(
                            user.getId(),
                            customer.getMessagePrompt(),
                            ragContext,
                            availableFunctions,
                            threadBody);
        }

        LOG.debug("Generation response:\n{}", generationResponse);

        try {
            LlmServiceUtil.validateHtmlBody(generationResponse.text());
        } catch (InvalidHtmlBodyException e) {
            exceptionManager.handleException(e);
            return Optional.empty();
        }

        MessageLogEntry messageLogEntry =
                apiClient.createMessageLogEntry(
                        user.getCustomerId(),
                        user.getId(),
                        fromEmailAddress,
                        subject,
                        receivedAt,
                        categorisationResponse,
                        generationResponse,
                        messageCategory);

        URL ratingUrl =
                LlmServiceUtil.buildRatingUrl(
                        user.getSettings(),
                        messageLogEntry,
                        mailflowFrontendUrl + RESPONSE_RATING_URI);

        return Optional.of(
                LlmServiceUtil.createHtmlMessage(
                        generationResponse.text(), user, customer, ratingUrl));
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

    private CategorisationAgent getOrCreateCategorisationAgent(Customer customer) {
        return categorisationAgentsByCustomer.computeIfAbsent(
                customer.getId(),
                id -> new CategorisationAgent(AesUtil.decrypt(customer.getOpenaiApiKey()), debug));
    }

    private GenerationAgent getOrCreateGenerationAgent(Customer customer) {
        return generationsAgentsByCustomer.computeIfAbsent(
                customer.getId(), id -> new GenerationAgent(customer, debug));
    }

    private String fetchRagContext(
            long customerId, long userId, List<ThreadMessage> messageThread) {
        RagServiceResponse ragResponse =
                ragServiceClient.search(new RagServiceRequest(customerId, userId, messageThread));
        if (ragResponse != null && !ragResponse.relevantSegments().isEmpty()) {
            return LlmServiceUtil.formatRagServiceResponse(ragResponse);
        }
        return null;
    }
}
