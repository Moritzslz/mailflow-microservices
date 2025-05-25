package de.flowsuite.llmservice.service;

import de.flowsuite.llmservice.agent.CategorisationAgent;
import de.flowsuite.llmservice.agent.GenerationAgent;
import de.flowsuite.llmservice.common.ModelResponse;
import de.flowsuite.llmservice.util.LlmServiceUtil;
import de.flowsuite.mailflow.common.entity.Customer;
import de.flowsuite.mailflow.common.entity.MessageCategory;
import de.flowsuite.mailflow.common.entity.User;
import de.flowsuite.mailflow.common.exception.IdConflictException;
import de.flowsuite.mailflow.common.util.AesUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LlmService {

    private static final Logger LOG = LoggerFactory.getLogger(LlmService.class);
    private static final String GET_CUSTOMER_URI = "/customers/{customerId}";
    private static final String POST_MESSAGE_LOG_ENTRY_URI =
            "/customers/{customerId}/users/{userId}/message-log";

    private static final ConcurrentHashMap<Long, Customer> customers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, CategorisationAgent> categorisationAgents =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, GenerationAgent> generationsAgents =
            new ConcurrentHashMap<>();

    private final boolean debug;
    private final RestClient apiRestClient;

    public LlmService(
            @Value("${langchain.debug}") boolean debug,
            @Qualifier("apiRestClient") RestClient apiRestClient) {
        this.debug = debug;
        this.apiRestClient = apiRestClient;
    }

    public MessageCategory categoriseMessage(
            User user, String message, List<MessageCategory> categories) {
        LOG.info("Categorising message for user {}", user.getId());

        Customer customer = getOrFetchCustomer(user);

        LOG.debug("Categories: {}", categories);

        String formattedCategories = LlmServiceUtil.formatCategories(categories);

        LOG.debug("Formatted categories: {}", formattedCategories);

        CategorisationAgent categorisationAssistant =
                getOrCreateCategorisationAgent(customer, formattedCategories);
        ModelResponse response = categorisationAssistant.categorise(user.getId(), message);

        LOG.debug("Categorisation response: {}", response);

        String category = response.text();

        for (MessageCategory messageCategory : categories) {
            if (messageCategory.getCategory().equalsIgnoreCase(category)) {
                return messageCategory;
            }
        }

        return null;
    }

    public String generateReply(User user, String messageThread, MessageCategory messageCategory) {
        LOG.info("Generating reply message for user {}", user.getId());

        Customer customer = getOrFetchCustomer(user);

        // Todo call rag service

        String context = "No context";

        GenerationAgent generationAgent = getOrCreateGenerationAgent(customer);

        ModelResponse response;
        if (!messageCategory.getFunctionCall()) {
            response =
                    generationAgent.generateContextualReply(
                            user.getId(), null, context, messageThread);
        } else {
            response =
                    generationAgent.generateContextualReplyWithFunctionCall(
                            user.getId(), null, context, "none", messageThread);
        }

        LOG.debug("Reply response: {}", response);

        // TODO post message log entry

        // TODO response rating with message log token

        return LlmServiceUtil.createHtmlMessage(response.text());
    }

    public void onCustomerUpdated(long customerId, Customer customer) {
        LOG.debug("Updating customer {}", customerId);

        if (!customer.getId().equals(customerId)) {
            throw new IdConflictException();
        }

        customers.put(customerId, customer);
    }

    private Customer getOrFetchCustomer(User user) {
        return customers.computeIfAbsent(user.getCustomerId(), id -> fetchCustomerByUser(user));
    }

    private Customer fetchCustomerByUser(User user) {
        LOG.debug("Fetching customer by user {}", user.getId());

        // Blocking request
        return apiRestClient
                .get()
                .uri(GET_CUSTOMER_URI, user.getCustomerId())
                .retrieve()
                .body(Customer.class);
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
