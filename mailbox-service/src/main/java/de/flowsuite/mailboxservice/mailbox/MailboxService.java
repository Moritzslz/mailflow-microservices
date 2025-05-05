package de.flowsuite.mailboxservice.mailbox;

import com.sun.mail.imap.IMAPFolder;

import de.flowsuite.mailboxservice.exception.MailboxException;
import de.flowsuite.mailboxservice.exception.MailboxNotFoundException;
import de.flowsuite.mailboxservice.exception.MailboxServiceExceptionHandler;
import de.flowsuite.mailflow.common.entity.User;

import jakarta.mail.Store;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.*;

@Service
class MailboxService {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(MailboxService.class);
    private static final String LIST_USERS_URI = "/customers/users";
    private static final ConcurrentHashMap<Long, Future<Void>> futures = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, MailboxListenerTask> tasks =
            new ConcurrentHashMap<>();
    static final int MAX_RETRIES = 3;
    static final long RETRY_DELAY_MILLIS = 5000;
    static final long TIMEOUT_MILLIS = RETRY_DELAY_MILLIS * (MAX_RETRIES + 1);

    private final RestClient restClient;
    private final MailboxConnectionHandler mailboxConnectionHandler;
    private final ExecutorService mailboxExecutor;
    private final MailboxServiceExceptionHandler mailboxServiceExceptionHandler;

    public MailboxService(
            RestClient restClient,
            MailboxConnectionHandler mailboxConnectionHandler,
            MailboxServiceExceptionHandler mailboxServiceExceptionHandler) {
        this.restClient = restClient;
        this.mailboxConnectionHandler = mailboxConnectionHandler;
        this.mailboxExecutor = Executors.newCachedThreadPool();
        this.mailboxServiceExceptionHandler = mailboxServiceExceptionHandler;
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

        LOG.info("Found {} users", users.size());

        for (User user : users) {
            try {
                startMailboxListenerForUser(user);
            } catch (MailboxException e) {
                mailboxServiceExceptionHandler.handleException(e);
            }
        }
    }

    private void startMailboxListenerForUser(User user) throws MailboxException {
        LOG.info("Starting mailbox listener for user {}", user.getId());

        MailboxServiceUtil.validateUserSettings(user.getId(), user.getSettings());

        if (!user.getSettings().isExecutionEnabled()) {
            LOG.info("Aborting: execution is disabled for user {}", user.getId());
            return;
        }

        MailboxListenerTask task =
                new MailboxListenerTask(
                        user, mailboxConnectionHandler, mailboxServiceExceptionHandler);
        Future<Void> future = mailboxExecutor.submit(task);

        // Block until store and inbox are ready or timeout occurs
        getStore(task, future, user.getId());
        getInbox(task, future, user.getId());

        futures.put(user.getId(), future);
        tasks.put(user.getId(), task);
    }

    void onUserCreated(User createdUser) throws MailboxException {
        startMailboxListenerForUser(createdUser);
    }

    // TODO fix
    void onUserUpdated(User updatedUser) throws MailboxException {
        LOG.info("Restarting mailbox listener for user {} due to update", updatedUser.getId());

        MailboxListenerTask task = tasks.get(updatedUser.getId());
        Future<Void> future = futures.get(updatedUser.getId());

        if (task == null || future == null || future.isDone() || future.isCancelled()) {
            throw new MailboxNotFoundException(updatedUser.getId());
        }

        Store store = getStore(task, future, updatedUser.getId());
        IMAPFolder inbox = getInbox(task, future, updatedUser.getId());

        mailboxConnectionHandler.closeConnection(inbox, store);

        terminateMailboxListenerTaskForUser(future, updatedUser.getId());

        startMailboxListenerForUser(updatedUser);
    }

    private Store getStore(MailboxListenerTask task, Future<Void> future, long userId)
            throws MailboxException {
        // Block until store is ready or timeout occurs
        try {
            return task.getStore();
        } catch (MailboxException e) {
            terminateMailboxListenerTaskForUser(future, userId);
            throw new MailboxException(
                    String.format("Mailbox listener task failed for user %d", userId),
                    e,
                    e.shouldNotifyAdmin());
        }
    }

    private IMAPFolder getInbox(MailboxListenerTask task, Future<Void> future, long userId)
            throws MailboxException {
        // Block until store is ready or timeout occurs
        try {
            return task.getInbox();
        } catch (MailboxException e) {
            terminateMailboxListenerTaskForUser(future, userId);
            throw new MailboxException(
                    String.format("Mailbox listener task failed for user %d", userId),
                    e,
                    e.shouldNotifyAdmin());
        }
    }

    private void terminateMailboxListenerTaskForUser(Future<Void> future, long userId) {
        future.cancel(true);

        if (!future.isDone()) {
            LOG.warn("Failed to cancel mailbox listener for user {}", userId);
            return;
        }

        futures.remove(userId);
        tasks.remove(userId);
    }
}
