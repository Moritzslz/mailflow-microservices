package de.flowsuite.mailboxservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(code = HttpStatus.BAD_REQUEST)
public class InvalidSettingsException extends RuntimeException {

    public InvalidSettingsException(long userId) {
        super("Mailbox settings for user " + userId + " are missing or invalid.");
    }
}
