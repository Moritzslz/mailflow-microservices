package de.flowsuite.shared.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ExceptionManager {

    private static final Logger LOG = LoggerFactory.getLogger(ExceptionManager.class);

    public void handleException(Exception e) {
        handleException(e, true);
    }

    public void handleException(Exception e, boolean log) {
        if (e instanceof ServiceException exception) {
            if (exception.shouldNotifyAdmin()) {
                if (log) {
                    LOG.error("Service exception occurred. Notifying admin. Error:", exception);
                }
                // TODO: Notify admin
            } else if (log) {
                LOG.warn(
                        "Handled service exception (no admin notification required): {}",
                        exception.getMessage());
            }
        } else {
            if (log) {
                LOG.error("Unexpected exception occurred. Notifying admin. Error:", e);
            }
            // TODO: Notify admin
        }
    }
}
