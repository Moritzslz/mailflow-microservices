package de.flowsuite.mailboxservice.mailbox;

import de.flowsuite.mailboxservice.exception.MailboxServiceExceptionManager;
import de.flowsuite.mailflow.common.entity.User;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/notifications")
class MailboxResource {

    private final MailboxService mailboxService;
    private final MailboxServiceExceptionManager exceptionManager;

    MailboxResource(
            MailboxService mailboxService, MailboxServiceExceptionManager exceptionManager) {
        this.mailboxService = mailboxService;
        this.exceptionManager = exceptionManager;
    }

    @PostMapping("/users/{userId}")
    ResponseEntity<Void> onUserCreated(@PathVariable long userId, @RequestBody User user)
            throws Exception {
        try {
            mailboxService.onUserCreated(userId, user);
        } catch (Exception e) {
            exceptionManager.handleException(e, false);
            throw e;
        }
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{userId}")
    ResponseEntity<Void> onUserUpdated(@PathVariable long userId, @RequestBody User user)
            throws Exception {
        try {
            mailboxService.onUserUpdated(userId, user);
        } catch (Exception e) {
            exceptionManager.handleException(e, false);
            throw e;
        }
        return ResponseEntity.noContent().build();
    }
}
