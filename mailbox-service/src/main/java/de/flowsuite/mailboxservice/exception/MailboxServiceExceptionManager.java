package de.flowsuite.mailboxservice.exception;

import static de.flowsuite.mailboxservice.mailbox.MailboxService.futures;
import static de.flowsuite.mailboxservice.mailbox.MailboxService.tasks;
import static de.flowsuite.mailboxservice.message.MessageService.blacklist;
import static de.flowsuite.mailboxservice.message.MessageService.messageCategories;

import de.flowsuite.mailboxservice.mailbox.MailboxListenerTask;
import de.flowsuite.mailboxservice.mailbox.MailboxService;
import de.flowsuite.mailflow.common.entity.User;
import de.flowsuite.shared.exception.ExceptionManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;

@Service
public class MailboxServiceExceptionManager extends ExceptionManager {

    private static final Logger LOG = LoggerFactory.getLogger(MailboxServiceExceptionManager.class);
    private static final ConcurrentHashMap<Long, Integer> retryAttempts = new ConcurrentHashMap<>();
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final MailboxService mailboxService;
    private final ScheduledExecutorService retryExecutor;

    MailboxServiceExceptionManager(@Lazy MailboxService mailboxService) {
        this.mailboxService = mailboxService;
        // TODO this might cause bottlenecks if multiple listeners fail at the same time
        this.retryExecutor =
                Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "Retry-Executor"));
    }

    public void handleMailboxListenerFailure(User user, MailboxException e) {
        retryExecutor.execute(
                () -> {
                    LOG.debug("Handling mailbox listener failure for user {}", user.getId());
                    cleanUpMailboxListenerTask(user);
                    try {
                        retryMailboxListenerTask(user, e);
                    } catch (MaxRetriesException maxRetriesException) {
                        handleException(maxRetriesException);
                    }
                });
    }

    private void cleanUpMailboxListenerTask(User user) {
        LOG.debug("Cleaning up mailbox listener task for user {}", user.getId());

        MailboxListenerTask task = tasks.remove(user.getId());
        Future<Void> future = futures.remove(user.getId());
        messageCategories.remove(user.getId());
        blacklist.remove(user.getId());

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
                            true);
            handleException(mailboxException);
        }
    }

    private void retryMailboxListenerTask(User user, MailboxException e)
            throws MaxRetriesException {
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
