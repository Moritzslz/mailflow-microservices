package de.flowsuite.ragservice.resource;

import de.flowsuite.mailflow.common.dto.RagServiceRequest;
import de.flowsuite.mailflow.common.dto.RagServiceResponse;
import de.flowsuite.mailflow.common.entity.MessageCategory;
import de.flowsuite.mailflow.common.entity.RagUrl;
import de.flowsuite.ragservice.service.RagService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
class RagServiceResource {

    private final RagService ragService;

    RagServiceResource(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/search")
    ResponseEntity<RagServiceResponse> search(@RequestBody RagServiceRequest request) {
        Optional<RagServiceResponse> response =
                ragService.search(request.userId(), request.customerId(), request.messageThread());
        return response.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/notifications/customers/{customerId}/rag-urls/{id}")
    ResponseEntity<MessageCategory> onRagUrlCreated(
            @PathVariable long customerId, @PathVariable long id, @RequestBody RagUrl ragUrl) {
        ragService.onRagUrlCreated(customerId, id, ragUrl);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/notifications/customers/{customerId}/rag-urls/{id}")
    ResponseEntity<MessageCategory> onRagUrlDeleted(
            @PathVariable long customerId, @PathVariable long id, @RequestBody RagUrl ragUrl) {
        ragService.onRagUrlDeleted(customerId, id, ragUrl);
        return ResponseEntity.noContent().build();
    }
}
