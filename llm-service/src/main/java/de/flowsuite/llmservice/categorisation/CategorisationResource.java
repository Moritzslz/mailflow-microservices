package de.flowsuite.llmservice.categorisation;

import de.flowsuite.mailflow.common.entity.Customer;
import de.flowsuite.mailflow.common.entity.MessageCategory;
import de.flowsuite.mailflow.common.entity.User;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class CategorisationResource {

    private final CategorisationService categorisationService;

    CategorisationResource(CategorisationService categorisationService) {
        this.categorisationService = categorisationService;
    }

    @PostMapping("/categorisation")
    ResponseEntity<MessageCategory> categorise(
            User user, String text, List<MessageCategory> categories) {
        return ResponseEntity.ok(categorisationService.categorise(user, text, categories));
    }

    @PostMapping("/notifications/customers/{customerId}")
    ResponseEntity<MessageCategory> onCustomerUpdated(
            @PathVariable long customerId, @RequestBody Customer customer) {
        categorisationService.onCustomerUpdated(customerId, customer);
        return ResponseEntity.noContent().build();
    }
}
