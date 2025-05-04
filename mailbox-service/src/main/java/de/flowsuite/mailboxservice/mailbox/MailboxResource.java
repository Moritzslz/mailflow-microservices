package de.flowsuite.mailboxservice.mailbox;

import de.flowsuite.mailflow.common.entity.User;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/notifications")
class MailboxResource {

    private final MailboxService mailboxService;

    public MailboxResource(MailboxService mailboxService) {
        this.mailboxService = mailboxService;
    }

    @PostMapping("/users")
    ResponseEntity<Void> onUserCreated(@RequestBody User user) {
        mailboxService.onUserCreated(user);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PutMapping("/users")
    ResponseEntity<Void> onUserUpdated(@RequestBody User user) {
        mailboxService.onUserUpdated(user);
        return ResponseEntity.ok().build();
    }
}
