package de.flowsuite.mailboxservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(code = HttpStatus.BAD_REQUEST)
public class InvalidSettingsException extends MailboxException {

    public InvalidSettingsException(String message) {
        super(message, false);
    }

    public InvalidSettingsException(long userId) {
        super(String.format("Mailbox settings for user %s are missing or invalid", userId), false);
    }
}
