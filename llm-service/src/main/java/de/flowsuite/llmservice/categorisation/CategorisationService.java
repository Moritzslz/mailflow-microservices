package de.flowsuite.llmservice.categorisation;

import de.flowsuite.mailflow.common.entity.Customer;
import de.flowsuite.mailflow.common.entity.MessageCategory;
import de.flowsuite.mailflow.common.entity.User;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
class CategorisationService {

    private static final ConcurrentHashMap<Long, Customer> customers = new ConcurrentHashMap<>();

    MessageCategory categorise(User user, String text, List<MessageCategory> categories) {}

    private Customer getOrFetchCustomer(User user) {
        return customers.computeIfAbsent(
                user.getCustomerId(), id -> fetchMessageCategoriesByUser(user));
    }

    private Customer fetchCustomerByUser(User user) {}
}
