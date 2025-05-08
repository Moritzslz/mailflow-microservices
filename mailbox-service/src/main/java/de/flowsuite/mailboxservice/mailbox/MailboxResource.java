package de.flowsuite.mailboxservice.mailbox;

import de.flowsuite.mailboxservice.exception.MailboxException;
import de.flowsuite.mailflow.common.entity.User;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/notifications")
class MailboxResource {

    private final MailboxService mailboxService;
    private final MailboxExceptionManager mailboxExceptionManager;

    public MailboxResource(
            MailboxService mailboxService, MailboxExceptionManager mailboxExceptionManager) {
        this.mailboxService = mailboxService;
        this.mailboxExceptionManager = mailboxExceptionManager;
    }

    @PostMapping("/users")
    ResponseEntity<Void> onUserCreated(@RequestBody User user) throws MailboxException {
        try {
            mailboxService.onUserCreated(user);
        } catch (MailboxException e) {
            mailboxExceptionManager.handleException(e, false);
            throw e;
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PutMapping("/users")
    ResponseEntity<Void> onUserUpdated(@RequestBody User user) throws MailboxException {
        try {
            mailboxService.onUserUpdated(user);
        } catch (MailboxException e) {
            mailboxExceptionManager.handleException(e, false);
            throw e;
        }
        return ResponseEntity.ok().build();
    }
}
