package de.flowsuite.llmservice.resource;

import de.flowsuite.llmservice.service.LlmService;
import de.flowsuite.mailflow.common.dto.CategorisationRequest;
import de.flowsuite.mailflow.common.dto.CategorisationResponse;
import de.flowsuite.mailflow.common.dto.GenerationRequest;
import de.flowsuite.mailflow.common.entity.Customer;
import de.flowsuite.mailflow.common.entity.MessageCategory;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.Optional;

@RestController
class LlmServiceResource {

    private final LlmService llmService;

    LlmServiceResource(LlmService llmService) {
        this.llmService = llmService;
    }

    @PostMapping("/categorisation")
    ResponseEntity<CategorisationResponse> categoriseMessage(
            @RequestBody CategorisationRequest request) {
        Optional<CategorisationResponse> response =
                llmService.categoriseMessage(request.user(), request.text(), request.categories());
        return response.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/generation")
    ResponseEntity<String> generateReply(@RequestBody GenerationRequest request)
            throws MalformedURLException, URISyntaxException {
        Optional<String> response =
                llmService.generateReply(
                        request.user(),
                        request.text(),
                        request.fromEmailAddress(),
                        request.subject(),
                        request.receivedAt(),
                        request.categorisationResponse());
        return response.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/notifications/customers/{customerId}")
    ResponseEntity<MessageCategory> onCustomerUpdated(
            @PathVariable long customerId, @RequestBody Customer customer) {
        llmService.onCustomerUpdated(customerId, customer);
        return ResponseEntity.noContent().build();
    }
}
