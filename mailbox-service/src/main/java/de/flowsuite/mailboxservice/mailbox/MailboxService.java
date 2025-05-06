package de.flowsuite.mailboxservice.mailbox;

import de.flowsuite.mailboxservice.exception.MailboxException;
import de.flowsuite.mailboxservice.exception.MailboxNotFoundException;
import de.flowsuite.mailflow.common.entity.User;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.*;

@Service
class MailboxService {

    // spotless:off
    private static final Logger LOG = LoggerFactory.getLogger(MailboxService.class);
    private static final String LIST_USERS_URI = "/customers/users";

    static final ConcurrentHashMap<Long, MailboxListenerTask> tasks = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<Long, Future<Void>> futures = new ConcurrentHashMap<>();
    static final long TIMEOUT_MILLISECONDS = 3000;

    private final RestClient restClient;
    private final MailboxConnectionManager mailboxConnectionManager;
    private final ExecutorService mailboxExecutor;
    private final MailboxExceptionManager mailboxExceptionManager;
    // spotless:on

    public MailboxService(
            RestClient restClient,
            MailboxConnectionManager mailboxConnectionManager,
            @Lazy MailboxExceptionManager mailboxExceptionManager) {
        this.restClient = restClient;
        this.mailboxConnectionManager = mailboxConnectionManager;
        this.mailboxExecutor = Executors.newCachedThreadPool();
        this.mailboxExceptionManager = mailboxExceptionManager;
    }

    @EventListener(ApplicationReadyEvent.class)
    void startMailboxService() {
        LOG.info("Starting mailbox service");

        // Blocking request
        List<User> users =
                restClient
                        .get()
                        .uri(LIST_USERS_URI)
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<User>>() {});

        LOG.info("Received {} users from API", users.size());

        for (User user : users) {
            try {
                startMailboxListenerForUser(user);
            } catch (Exception e) {
                mailboxExceptionManager.handleException(e);
            }
        }
    }

    void startMailboxListenerForUser(User user) throws MailboxException {
        startMailboxListenerForUser(user, false);
    }

    void startMailboxListenerForUser(User user, boolean shouldDelayStart) throws MailboxException {
        LOG.info("Starting mailbox listener for user {}", user.getId());

        MailboxServiceUtil.validateUserSettings(user.getId(), user.getSettings());

        if (!user.getSettings().isExecutionEnabled()) {
            LOG.info("Aborting: execution is disabled for user {}", user.getId());
            return;
        }

        MailboxListenerTask task =
                new MailboxListenerTask(user, mailboxConnectionManager, mailboxExceptionManager, shouldDelayStart);
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
        }

        tasks.put(user.getId(), task);
        futures.put(user.getId(), future);

        LOG.info("Mailbox listener for user {} started successfully", user.getId());
    }

    void onUserCreated(User createdUser) throws MailboxException {
        startMailboxListenerForUser(createdUser);
    }

    // TODO fix
    void onUserUpdated(User updatedUser) throws MailboxException {
        LOG.info("Restarting mailbox listener for user {} due to update", updatedUser.getId());

        MailboxListenerTask task = tasks.get(updatedUser.getId());
        Future<Void> future = futures.get(updatedUser.getId());

        if (task == null || future == null) {
            throw new MailboxNotFoundException(updatedUser.getId());
        }

        terminateMailboxListenerForUser(task, future, updatedUser.getId());

        startMailboxListenerForUser(updatedUser, true);
    }

    void terminateMailboxListenerForUser(MailboxListenerTask task, Future<Void> future, long userId)
            throws MailboxException {
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

        futures.remove(userId);
        tasks.remove(userId);

        LOG.info("Mailbox listener for user {} fully terminated successfully", userId);
    }
}
