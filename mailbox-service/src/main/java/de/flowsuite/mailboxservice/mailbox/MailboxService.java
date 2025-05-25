package de.flowsuite.mailboxservice.mailbox;

import de.flowsuite.mailboxservice.exception.MailboxException;
import de.flowsuite.mailboxservice.exception.MailboxServiceExceptionManager;
import de.flowsuite.mailflow.common.entity.User;
import de.flowsuite.mailflow.common.exception.IdConflictException;
import de.flowsuite.mailflow.common.util.Util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.*;

@Service
public class MailboxService {

    // spotless:off
    private static final Logger LOG = LoggerFactory.getLogger(MailboxService.class);
    private static final String LIST_USERS_URI = "/customers/users";
    private static final CountDownLatch restartLatch = new CountDownLatch(1);
    private static final long RESTART_DELAY_MILLISECONDS = 5000;

    public static final ConcurrentHashMap<Long, MailboxListenerTask> tasks = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<Long, Future<Void>> futures = new ConcurrentHashMap<>();
    static final long TIMEOUT_MILLISECONDS = 3000;

    private final RestClient apiRestClient;
    private final MailboxConnectionManager mailboxConnectionManager;
    private final ExecutorService mailboxExecutor;
    private final MailboxServiceExceptionManager exceptionManager;
    // spotless:on

    MailboxService(
            @Qualifier("apiRestClient") RestClient apiRestClient,
            MailboxConnectionManager mailboxConnectionManager,
            @Lazy MailboxServiceExceptionManager exceptionManager) {
        this.apiRestClient = apiRestClient;
        this.mailboxConnectionManager = mailboxConnectionManager;
        this.mailboxExecutor = Executors.newCachedThreadPool();
        this.exceptionManager = exceptionManager;
    }

    @EventListener(ApplicationReadyEvent.class)
    void startMailboxService() {
        LOG.info("Starting mailbox service");

        // TODO handle test version

        List<User> users = null;

        try {
            // Blocking request
            users =
                    apiRestClient
                            .get()
                            .uri(LIST_USERS_URI)
                            .retrieve()
                            .body(new ParameterizedTypeReference<List<User>>() {});
        } catch (Exception e) {
            exceptionManager.handleException(
                    new MailboxException("Failed to fetch users", e, true));
        }

        if (users == null || users.isEmpty()) {
            if (restartLatch.getCount() == 1) {
                restartLatch.countDown();
                LOG.error(
                        "No users found. Restarting once in {} seconds.",
                        (float) RESTART_DELAY_MILLISECONDS / 1000);
                try {
                    Thread.sleep(RESTART_DELAY_MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                startMailboxService();
            } else {
                MailboxException mailboxException =
                        new MailboxException("No users found. Giving up.", true);
                exceptionManager.handleException(mailboxException);
            }
            return;
        }

        LOG.info("Received {} users from API", users.size());

        for (User user : users) {
            try {
                startMailboxListenerForUser(user);
            } catch (Exception e) {
                exceptionManager.handleException(e);
            }
        }
    }

    public void startMailboxListenerForUser(User user) throws MailboxException {
        startMailboxListenerForUser(user, false);
    }

    void startMailboxListenerForUser(User user, boolean shouldDelayStart) throws MailboxException {
        LOG.info("Starting mailbox listener for user {}", user.getId());

        Util.validateMailboxSettings(
                user.getSettings().getImapHost(),
                user.getSettings().getSmtpHost(),
                user.getSettings().getImapPort(),
                user.getSettings().getSmtpPort());

        if (tasks.containsKey(user.getId()) || futures.containsKey(user.getId())) {
            LOG.info("Aborting: mailbox listener for user {} is already running", user.getId());
            return;
        }

        if (!user.getSettings().isExecutionEnabled()) {
            LOG.info("Aborting: execution is disabled for user {}", user.getId());
            return;
        }

        MailboxListenerTask task =
                new MailboxListenerTask(
                        user, mailboxConnectionManager, exceptionManager, shouldDelayStart);
        Future<Void> future = mailboxExecutor.submit(task);

        // Block until task is active or timeout occurs
        if (!task.hasEnteredImapIdleMode()) {
            future.cancel(true);
            throw new MailboxException(
                    String.format(
                            "Failed to start mailbox listener for user %d: The inbox did not enter"
                                    + " IDLE mode within the expected timeout period.",
                            user.getId()),
                    true);
            // TODO automatic retry?
        }

        tasks.put(user.getId(), task);
        futures.put(user.getId(), future);

        LOG.info("Mailbox listener for user {} started successfully", user.getId());
    }

    void onUserCreated(long userId, User createdUser) throws MailboxException {
        LOG.info("New user received {}", createdUser.getId());

        if (!createdUser.getId().equals(userId)) {
            throw new IdConflictException();
        }

        startMailboxListenerForUser(createdUser);
    }

    void onUserUpdated(long userId, User updatedUser) throws MailboxException {
        LOG.info("Restarting mailbox listener for user {} due to update", updatedUser.getId());

        if (!updatedUser.getId().equals(userId)) {
            throw new IdConflictException();
        }

        MailboxListenerTask task = tasks.get(updatedUser.getId());
        Future<Void> future = futures.get(updatedUser.getId());

        if (task != null && future != null) {
            terminateMailboxListenerForUser(task, future, updatedUser.getId());
        }

        startMailboxListenerForUser(updatedUser, true);
    }

    public void terminateMailboxListenerForUser(
            MailboxListenerTask task, Future<Void> future, long userId) throws MailboxException {
        LOG.info("Terminating mailbox listener for user {}", userId);

        try {
            task.disconnect();
        } catch (MailboxException e) {
            throw new MailboxException(
                    String.format("Failed to terminate mailbox listener of user %d", userId),
                    e,
                    e.shouldNotifyAdmin());
        }

        // Cancel the task and interrupt the thread
        future.cancel(true);

        try {
            future.get(
                    TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS); // Wait for task to fully finish
        } catch (CancellationException | TimeoutException e) {
            throw new MailboxException(
                    String.format(
                            "Mailbox listener for user %d did not terminate cleanly in time",
                            userId),
                    e,
                    false);
        } catch (Exception e) {
            throw new MailboxException(
                    String.format(
                            "Error while waiting for mailbox listener to terminate for user %d",
                            userId),
                    e,
                    false);
        }

        tasks.remove(userId);
        futures.remove(userId);

        LOG.info("Mailbox listener for user {} fully terminated successfully", userId);
    }
}
