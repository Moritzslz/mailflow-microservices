package de.flowsuite.mailboxservice.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MailboxServiceExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MailboxServiceExceptionHandler.class);

    public void handleException(Exception e) {
        if (e instanceof MailboxException mailboxException) {
            if (mailboxException.shouldNotifyAdmin()) {
                LOG.error(
                        "Unexpected failure. Notifying admin: {}",
                        mailboxException.shouldNotifyAdmin(),
                        mailboxException);
                // TODO
            }
        } else {
            LOG.error("Unexpected failure", e);
        }
    }
}
