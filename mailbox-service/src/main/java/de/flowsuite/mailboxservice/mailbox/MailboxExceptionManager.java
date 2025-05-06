package de.flowsuite.mailboxservice.mailbox;

import static de.flowsuite.mailboxservice.mailbox.MailboxService.futures;
import static de.flowsuite.mailboxservice.mailbox.MailboxService.tasks;

import de.flowsuite.mailboxservice.exception.MailboxException;
import de.flowsuite.mailboxservice.exception.MaxRetriesException;
import de.flowsuite.mailflow.common.entity.User;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;

@Service
class MailboxExceptionManager {

    private static final Logger LOG = LoggerFactory.getLogger(MailboxExceptionManager.class);
    private static final ConcurrentHashMap<Long, Integer> retryAttempts = new ConcurrentHashMap<>();
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final MailboxService mailboxService;
    private final ScheduledExecutorService retryExecutor;

    public MailboxExceptionManager(MailboxService mailboxService) {
        this.mailboxService = mailboxService;
        // TODO this might cause bottlenecks if multiple listeners fail at the same time
        this.retryExecutor =
                Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "Retry-Executor"));
    }

    void handleException(Exception e) {
        handleException(e, true);
    }

    void handleException(Exception e, boolean log) {
        if (e instanceof MailboxException mailboxException) {
            if (mailboxException.shouldNotifyAdmin()) {
                if (log) {
                    LOG.error(
                            "Mailbox exception occurred. Notifying admin. Error:",
                            mailboxException);
                }
                // TODO: Notify admin
            } else if (log) {
                LOG.warn(
                        "Handled mailbox exception (no admin notification required): {}",
                        mailboxException.getMessage());
            }
        } else {
            if (log) {
                LOG.error("Unexpected exception occurred. Notifying admin. Error:", e);
            }
            // TODO: Notify admin
        }
    }

    void handleMailboxListenerFailure(User user, MailboxException e) {
        retryExecutor.execute(
                () -> {
                    LOG.debug("Handling mailbox listener failure for user {}", user.getId());
                    cleanUpMailboxListenerTask(user, e);
                });
    }

    private void cleanUpMailboxListenerTask(User user, MailboxException e) {
        LOG.debug("Cleaning up mailbox listener task for user {}", user.getId());

        MailboxListenerTask task = tasks.remove(user.getId());
        Future<Void> future = futures.remove(user.getId());

        try {
            mailboxService.terminateMailboxListenerForUser(task, future, user.getId());
        } catch (MailboxException ex) {
            MailboxException mailboxException =
                    new MailboxException(
                            String.format(
                                    "Failed to terminate mailbox listener for user %d after"
                                            + " failure",
                                    user.getId()),
                            ex,
                            false);
            handleException(mailboxException);
        }

        try {
            retryMailboxListenerTask(user, e);
        } catch (MaxRetriesException maxRetriesException) {
            handleException(maxRetriesException);
        }
    }

    private void retryMailboxListenerTask(User user, MailboxException e) throws MaxRetriesException {
        retryAttempts.putIfAbsent(user.getId(), 1);

        int retry = retryAttempts.get(user.getId());
        long delaySeconds = (long) Math.pow(3, retry); // Exponential backoff

        if (retry > MAX_RETRY_ATTEMPTS) {
            retryAttempts.remove(user.getId());
            throw new MaxRetriesException(user.getId(), e);
        }

        LOG.warn(
                "Retrying mailbox listener task for user {}. Attempt {}/{}. Waiting {} seconds",
                user.getId(),
                retry,
                MAX_RETRY_ATTEMPTS,
                delaySeconds);

        retryAttempts.put(user.getId(), retry + 1);

        retryExecutor.schedule(
                () -> {
                    try {
                        mailboxService.startMailboxListenerForUser(user);
                    } catch (MailboxException ex) {
                        handleException(ex);
                    }
                },
                delaySeconds,
                TimeUnit.SECONDS);
    }
}
