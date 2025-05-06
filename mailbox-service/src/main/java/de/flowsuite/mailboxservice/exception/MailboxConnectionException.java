package de.flowsuite.mailboxservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(code = HttpStatus.INTERNAL_SERVER_ERROR)
public class MailboxConnectionException extends MailboxException {

    public MailboxConnectionException(String message, boolean notifyAdmin) {
        super(message, notifyAdmin);
    }

    public MailboxConnectionException(Throwable cause, boolean notifyAdmin) {
        super(cause, notifyAdmin);
    }

    public MailboxConnectionException(String message, Throwable cause, boolean notifyAdmin) {
        super(message, cause, notifyAdmin);
    }
}
