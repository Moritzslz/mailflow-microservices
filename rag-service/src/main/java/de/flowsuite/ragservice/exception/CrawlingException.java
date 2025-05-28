package de.flowsuite.ragservice.exception;

import de.flowsuite.shared.exception.ServiceException;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
public class CrawlingException extends ServiceException {

    public CrawlingException(String message) {
        super(message, false);
    }

    public CrawlingException(String message, Throwable cause) {
        super(message, cause, false);
    }
}
