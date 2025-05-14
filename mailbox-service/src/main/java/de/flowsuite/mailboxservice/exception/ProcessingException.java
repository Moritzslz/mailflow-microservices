package de.flowsuite.mailboxservice.exception;

import de.flowsuite.shared.exception.ServiceException;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(code = HttpStatus.INTERNAL_SERVER_ERROR)
public class ProcessingException extends ServiceException {
    public ProcessingException(String message, boolean notifyAdmin) {
        super(message, notifyAdmin);
    }

    public ProcessingException(Throwable cause, boolean notifyAdmin) {
        super(cause, notifyAdmin);
    }

    public ProcessingException(String message, Throwable cause, boolean notifyAdmin) {
        super(message, cause, notifyAdmin);
    }
}
