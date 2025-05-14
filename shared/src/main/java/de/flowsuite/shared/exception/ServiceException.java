package de.flowsuite.shared.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(code = HttpStatus.INTERNAL_SERVER_ERROR)
public class ServiceException extends Exception {
    private final boolean notifyAdmin;

    public ServiceException(String message, boolean notifyAdmin) {
        super(message);
        this.notifyAdmin = notifyAdmin;
    }

    public ServiceException(Throwable cause, boolean notifyAdmin) {
        super(cause);
        this.notifyAdmin = notifyAdmin;
    }

    public ServiceException(String message, Throwable cause, boolean notifyAdmin) {
        super(message, cause);
        this.notifyAdmin = notifyAdmin;
    }

    public boolean shouldNotifyAdmin() {
        return notifyAdmin;
    }
}
