package de.flowsuite.ragservice.resource;

import de.flowsuite.mailflow.common.dto.RagServiceRequest;
import de.flowsuite.mailflow.common.entity.MessageCategory;
import de.flowsuite.mailflow.common.entity.RagUrl;
import de.flowsuite.ragservice.service.RagService;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
class RagServiceResource {

    private final RagService ragService;

    RagServiceResource(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/search/customers/{customerId}")
    ResponseEntity<List<EmbeddingMatch<TextSegment>>> search(
            @RequestBody RagServiceRequest request) {
        return ResponseEntity.ok(
                ragService.search(
                        request.user().getId(),
                        request.customer().getId(),
                        request.messageThread()));
    }

    @PostMapping("/notifications/customers/{customerId}/rag-urls/{id}")
    ResponseEntity<MessageCategory> onRagUrlCreated(
            @PathVariable long customerId, @PathVariable long id, @RequestBody RagUrl ragUrl) {
        ragService.onRagUrlCreated(customerId, id, ragUrl);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/notifications/customers/{customerId}/rag-urls/{id}")
    ResponseEntity<MessageCategory> onCustomerUpdated(
            @PathVariable long customerId, @PathVariable long id, @RequestBody RagUrl ragUrl) {
        ragService.onRagUrlDeleted(customerId, id, ragUrl);
        return ResponseEntity.noContent().build();
    }
}
