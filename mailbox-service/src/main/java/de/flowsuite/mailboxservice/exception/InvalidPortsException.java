package de.flowsuite.mailboxservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(code = HttpStatus.BAD_REQUEST)
public class InvalidPortsException extends RuntimeException {

    public InvalidPortsException(long userId, String allowedImapPorts, String allowedSmtpPorts) {
        super("Mailbox ports for user " + userId + " have not been configured properly. Allowed IMAP ports: " + allowedImapPorts + ". Allowed SMTP ports: " + allowedSmtpPorts);
    }
}
