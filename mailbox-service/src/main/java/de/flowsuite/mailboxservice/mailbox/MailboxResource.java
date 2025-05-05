package de.flowsuite.mailboxservice.mailbox;

import de.flowsuite.mailboxservice.exception.MailboxException;
import de.flowsuite.mailboxservice.exception.MailboxServiceExceptionHandler;
import de.flowsuite.mailflow.common.entity.User;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/notifications")
class MailboxResource {

    private final MailboxService mailboxService;
    private final MailboxServiceExceptionHandler mailboxServiceExceptionHandler;

    public MailboxResource(
            MailboxService mailboxService,
            MailboxServiceExceptionHandler mailboxServiceExceptionHandler) {
        this.mailboxService = mailboxService;
        this.mailboxServiceExceptionHandler = mailboxServiceExceptionHandler;
    }

    @PostMapping("/users")
    ResponseEntity<Void> onUserCreated(@RequestBody User user) {
        try {
            mailboxService.onUserCreated(user);
        } catch (MailboxException e) {
            mailboxServiceExceptionHandler.handleException(e);
            throw new RuntimeException(e);
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PutMapping("/users")
    ResponseEntity<Void> onUserUpdated(@RequestBody User user) {
        try {
            mailboxService.onUserUpdated(user);
        } catch (MailboxException e) {
            mailboxServiceExceptionHandler.handleException(e);
            throw new RuntimeException(e);
        }
        return ResponseEntity.ok().build();
    }
}
