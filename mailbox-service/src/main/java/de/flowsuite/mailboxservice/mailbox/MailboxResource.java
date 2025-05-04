package de.flowsuite.mailboxservice.mailbox;

import de.flowsuite.mailflow.common.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/notify/user")
class MailboxResource {

    private final MailboxService mailboxService;

    public MailboxResource(MailboxService mailboxService) {
        this.mailboxService = mailboxService;
    }

    @PostMapping
    ResponseEntity<Void> onUserCreated(@RequestBody @Valid User user) {
        mailboxService.onUserCreated(user);
        return ResponseEntity.ok().build();
    }

    @PutMapping
    ResponseEntity<Void> onUserUpdated(@RequestBody @Valid User user) {
        mailboxService.onUserUpdated(user);
        return ResponseEntity.ok().build();
    }
}
