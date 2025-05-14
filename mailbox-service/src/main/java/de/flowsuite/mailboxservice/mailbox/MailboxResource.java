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

    @PostMapping("/users")
    ResponseEntity<Void> onUserCreated(@RequestBody User user) throws Exception {
        try {
            mailboxService.onUserCreated(user);
        } catch (Exception e) {
            exceptionManager.handleException(e, false);
            throw e;
        }
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users")
    ResponseEntity<Void> onUserUpdated(@RequestBody User user) throws Exception {
        try {
            mailboxService.onUserUpdated(user);
        } catch (Exception e) {
            exceptionManager.handleException(e, false);
            throw e;
        }
        return ResponseEntity.noContent().build();
    }
}
