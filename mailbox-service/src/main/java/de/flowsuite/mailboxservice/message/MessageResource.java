package de.flowsuite.mailboxservice.message;

import de.flowsuite.mailflow.common.entity.BlacklistEntry;
import de.flowsuite.mailflow.common.entity.MessageCategory;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/notifications")
class MessageResource {

    private final MessageService messageService;

    MessageResource(MessageService messageService) {
        this.messageService = messageService;
    }

    @PutMapping("/customers/{customerId}/message-categories")
    ResponseEntity<Void> onMessageCategoriesUpdated(
            @PathVariable("customerId") long customerId,
            @RequestBody List<MessageCategory> messageCategories) {
        messageService.onMessageCategoriesUpdated(customerId, messageCategories);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("users/{userId}/blacklist")
    ResponseEntity<Void> onBlacklistUpdated(
            @PathVariable("userId") long userId, @RequestBody List<BlacklistEntry> blacklist) {
        messageService.onBlacklistUpdated(userId, blacklist);
        return ResponseEntity.noContent().build();
    }
}
