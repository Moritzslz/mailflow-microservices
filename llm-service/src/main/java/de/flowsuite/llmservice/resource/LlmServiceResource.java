package de.flowsuite.llmservice.resource;

import de.flowsuite.llmservice.service.LlmService;
import de.flowsuite.mailflow.common.dto.LlmServiceRequest;
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
class LlmServiceResource {

    private final LlmService llmService;

    LlmServiceResource(LlmService llmService) {
        this.llmService = llmService;
    }

    @PostMapping("/categorisation")
    ResponseEntity<MessageCategory> categoriseMessage(@RequestBody LlmServiceRequest request) {
        return ResponseEntity.ok(llmService.categoriseMessage(request.user(), request.text(), request.categories()));
    }

    @PostMapping("/generation")
    ResponseEntity<String> generateReply(@RequestBody LlmServiceRequest request) {
        return ResponseEntity.ok(llmService.generateReply(request.user(), request.text(), request.categories().get(0)));
    }

    @PostMapping("/notifications/customers/{customerId}")
    ResponseEntity<MessageCategory> onCustomerUpdated(
            @PathVariable long customerId, @RequestBody Customer customer) {
        llmService.onCustomerUpdated(customerId, customer);
        return ResponseEntity.noContent().build();
    }
}
