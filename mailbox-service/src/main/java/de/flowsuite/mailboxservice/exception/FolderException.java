package de.flowsuite.mailboxservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(code = HttpStatus.INTERNAL_SERVER_ERROR)
public class FolderException extends ProcessingException {

    public FolderException(String message) {
        super(message, false);
    }

    public FolderException(String message, boolean notifyAdmin) {
        super(message, notifyAdmin);
    }
}
