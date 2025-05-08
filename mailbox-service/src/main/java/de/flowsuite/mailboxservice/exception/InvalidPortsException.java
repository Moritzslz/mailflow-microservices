package de.flowsuite.mailboxservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(code = HttpStatus.BAD_REQUEST)
public class InvalidPortsException extends InvalidSettingsException {

    public InvalidPortsException(long userId, String allowedImapPorts, String allowedSmtpPorts) {
        super(
                String.format(
                        "Mailbox ports for user %s have not been configured properly. Allowed IMAP"
                                + " ports: %s. Allowed SMTP ports: %s",
                        userId, allowedImapPorts, allowedSmtpPorts));
    }
}
