package de.flowsuite.ragservice.resource;

import de.flowsuite.mailflow.common.dto.RagServiceRequest;
import de.flowsuite.mailflow.common.dto.RagServiceResponse;
import de.flowsuite.mailflow.common.entity.MessageCategory;
import de.flowsuite.mailflow.common.entity.RagUrl;
import de.flowsuite.ragservice.service.RagService;
import de.flowsuite.shared.exception.ExceptionManager;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
class RagServiceResource {

    private final RagService ragService;
    private final ExceptionManager exceptionManager;

    RagServiceResource(RagService ragService, ExceptionManager exceptionManager) {
        this.ragService = ragService;
        this.exceptionManager = exceptionManager;
    }

    @PostMapping("/search")
    ResponseEntity<RagServiceResponse> search(@RequestBody RagServiceRequest request) {
        try {
            Optional<RagServiceResponse> response =
                    ragService.search(
                            request.userId(), request.customerId(), request.messageThread());
            return response.map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.noContent().build());
        } catch (Exception e) {
            exceptionManager.handleException(e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/notifications/customers/{customerId}/rag-urls/{id}")
    ResponseEntity<MessageCategory> onRagUrlCreated(
            @PathVariable long customerId, @PathVariable long id, @RequestBody RagUrl ragUrl) {
        try {
            ragService.onRagUrlCreated(customerId, id, ragUrl);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            exceptionManager.handleException(e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PutMapping("/notifications/customers/{customerId}/rag-urls/{id}")
    ResponseEntity<MessageCategory> onRagUrlUpdated(
            @PathVariable long customerId, @PathVariable long id, @RequestBody RagUrl ragUrl) {
        try {
            ragService.onRagUrlUpdated(customerId, id, ragUrl);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            exceptionManager.handleException(e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/notifications/customers/{customerId}/rag-urls/{id}")
    ResponseEntity<MessageCategory> onRagUrlDeleted(
            @PathVariable long customerId, @PathVariable long id, @RequestBody RagUrl ragUrl) {
        try {
            ragService.onRagUrlDeleted(customerId, id, ragUrl);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            exceptionManager.handleException(e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
