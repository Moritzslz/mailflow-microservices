package de.flowsuite.mailboxservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(code = HttpStatus.NOT_FOUND)
public class MailboxNotFoundException extends MailboxException {

    public MailboxNotFoundException(long userId) {
        super(String.format("No active mailbox connection found for user %s", userId) ,false);
    }
}
