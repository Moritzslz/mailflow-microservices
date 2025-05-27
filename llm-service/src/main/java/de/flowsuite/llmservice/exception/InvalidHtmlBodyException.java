package de.flowsuite.llmservice.exception;

import de.flowsuite.shared.exception.ServiceException;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
public class InvalidHtmlBodyException extends ServiceException {

    public InvalidHtmlBodyException(String message) {
        super(message, true);
    }

    public InvalidHtmlBodyException(String message, Throwable cause) {
        super(message, cause, true);
    }
}
