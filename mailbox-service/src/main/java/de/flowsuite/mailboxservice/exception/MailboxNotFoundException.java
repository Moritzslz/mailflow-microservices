package de.flowsuite.mailboxservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(code = HttpStatus.NOT_FOUND)
public class MailboxNotFoundException extends RuntimeException {

    public MailboxNotFoundException(long userId) {
        super("No running mailbox connection found for user " + userId);
    }
}
