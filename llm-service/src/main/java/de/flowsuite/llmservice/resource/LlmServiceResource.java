package de.flowsuite.llmservice.resource;

import de.flowsuite.llmservice.service.LlmService;
import de.flowsuite.mailflow.common.dto.CategorisationRequest;
import de.flowsuite.mailflow.common.dto.CategorisationResponse;
import de.flowsuite.mailflow.common.dto.GenerationRequest;
import de.flowsuite.mailflow.common.entity.Customer;
import de.flowsuite.mailflow.common.entity.MessageCategory;
import de.flowsuite.shared.exception.ExceptionManager;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
class LlmServiceResource {

    private final LlmService llmService;
    private final ExceptionManager exceptionManager;

    LlmServiceResource(LlmService llmService, ExceptionManager exceptionManager) {
        this.llmService = llmService;
        this.exceptionManager = exceptionManager;
    }

    @PostMapping("/categorisation")
    ResponseEntity<CategorisationResponse> categoriseMessage(
            @RequestBody CategorisationRequest request) {
        try {
            Optional<CategorisationResponse> response =
                    llmService.categoriseMessage(
                            request.user(), request.text(), request.categories());
            return response.map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.noContent().build());
        } catch (Exception e) {
            exceptionManager.handleException(e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/generation")
    ResponseEntity<String> generateReply(@RequestBody GenerationRequest request) {
        try {
            Optional<String> response =
                    llmService.generateReply(
                            request.user(),
                            request.messageThread(),
                            request.fromEmailAddress(),
                            request.subject(),
                            request.receivedAt(),
                            request.categorisationResponse());
            return response.map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.noContent().build());
        } catch (Exception e) {
            exceptionManager.handleException(e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PutMapping("/notifications/customers/{customerId}")
    ResponseEntity<MessageCategory> onCustomerUpdated(
            @PathVariable long customerId, @RequestBody Customer customer) {
        try {
            llmService.onCustomerUpdated(customerId, customer);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            exceptionManager.handleException(e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
