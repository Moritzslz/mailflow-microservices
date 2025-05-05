package de.flowsuite.mailboxservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(code = HttpStatus.INTERNAL_SERVER_ERROR)
public class MailboxException extends Exception {
    private final boolean notifyAdmin;

    public MailboxException(String message, boolean notifyAdmin) {
        super(message);
        this.notifyAdmin = notifyAdmin;
    }

    public MailboxException(String message, Throwable cause, boolean notifyAdmin) {
        super(message, cause);
        this.notifyAdmin = notifyAdmin;
    }

    public boolean shouldNotifyAdmin() {
        return notifyAdmin;
    }
}
