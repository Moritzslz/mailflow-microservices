package de.flowsuite.ragservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
public class CrawlingException extends RuntimeException {

    public CrawlingException(String message) {
        super(message);
    }

    public CrawlingException(String message, Throwable cause) {
        super(message, cause);
    }
}
