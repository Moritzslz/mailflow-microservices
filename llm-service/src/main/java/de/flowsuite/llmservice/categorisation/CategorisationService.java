package de.flowsuite.llmservice.categorisation;

import de.flowsuite.mailflow.common.entity.Customer;
import de.flowsuite.mailflow.common.entity.MessageCategory;
import de.flowsuite.mailflow.common.entity.User;
import de.flowsuite.mailflow.common.exception.IdConflictException;
import de.flowsuite.mailflow.common.util.AesUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
class CategorisationService {

    private static final Logger LOG = LoggerFactory.getLogger(CategorisationService.class);
    private static final ConcurrentHashMap<Long, Customer> customers = new ConcurrentHashMap<>();
    private static final String GET_CUSTOMER_URI = "/customers/{customerId}";

    private final RestClient apiRestClient;

    CategorisationService(@Qualifier("apiRestClient") RestClient apiRestClient) {
        this.apiRestClient = apiRestClient;
    }

    MessageCategory categorise(User user, String message, List<MessageCategory> categories) {
        Customer customer = getOrFetchCustomer(user);
        CategorisationAgent categorisationAssistant =
                new CategorisationAgent(true, customer.getOpenaiApiKey(), categories);
        CategorisationAgent.CategorisationResult categorisationResult =
                categorisationAssistant.categorise(message);

        LOG.debug("Categorisation result: {}", categorisationResult);

        String category = categorisationResult.text();

        for (MessageCategory messageCategory : categories) {
            if (messageCategory.getCategory().equalsIgnoreCase(category)) {
                return messageCategory;
            }
        }

        return null;
    }

    private Customer getOrFetchCustomer(User user) {
        return customers.computeIfAbsent(user.getCustomerId(), id -> fetchCustomerByUser(user));
    }

    private Customer fetchCustomerByUser(User user) {
        LOG.debug("Fetching customer by user {}", user.getId());

        // Blocking request
        Customer customer =
                apiRestClient
                        .get()
                        .uri(GET_CUSTOMER_URI, user.getCustomerId())
                        .retrieve()
                        .body(Customer.class);

        customer.setOpenaiApiKey(AesUtil.decrypt(customer.getOpenaiApiKey()));

        return customer;
    }

    void onCustomerUpdated(long customerId, Customer customer) {
        LOG.debug("Updating customer {}", customerId);

        if (!customer.getId().equals(customerId)) {
            throw new IdConflictException();
        }

        customers.put(customerId, customer);
    }
}
