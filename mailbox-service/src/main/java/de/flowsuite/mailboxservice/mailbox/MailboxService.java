package de.flowsuite.mailboxservice.mailbox;

import de.flowsuite.mailboxservice.exception.MailboxNotFoundException;
import de.flowsuite.mailflow.common.entity.User;

import jakarta.mail.MessagingException;

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
    private static final ConcurrentHashMap<Long, Future<?>> mailboxFutures =
            new ConcurrentHashMap<>();
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MILLIS = 5000;

    private final RestClient restClient;
    private final MailboxConnectionHandler mailboxConnectionHandler;
    private final ExecutorService mailboxExecutor;

    public MailboxService(
            RestClient restClient, MailboxConnectionHandler mailboxConnectionHandler) {
        this.restClient = restClient;
        this.mailboxConnectionHandler = mailboxConnectionHandler;
        this.mailboxExecutor = Executors.newCachedThreadPool();
    }

    @EventListener(ApplicationReadyEvent.class)
    void startMailboxService() {
        LOG.info("Starting mailbox service.");

        // Blocking request
        List<User> users =
                restClient
                        .get()
                        .uri(LIST_USERS_URI)
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<User>>() {});

        for (User user : users) {
            try {
                startMailboxListenerForUser(user);
            } catch (Exception e) {
                LOG.error(e.getMessage(), e);
            }
        }
    }

    private void startMailboxListenerForUser(User user) {
        LOG.info("Starting mailbox listener for user {}.", user.getId());

        MailboxServiceUtil.validateUserSettings(user.getId(), user.getSettings());

        if (!user.getSettings().isExecutionEnabled()) {
            LOG.info("Aborting: execution is disabled for user {}.", user.getId());
            return;
        }

        Future<?> future =
                mailboxExecutor.submit(
                        () -> {
                            Thread.currentThread().setName("MailboxService-User-" + user.getId());

                            LOG.debug("Started new thread: {}", Thread.currentThread().getName());

                            int retryCount = 0;

                            while (!Thread.currentThread().isInterrupted()) {
                                try {
                                    mailboxConnectionHandler.listenToMailbox(user);
                                    // If listenToMailbox exits normally, break the retry loop.
                                    break;
                                } catch (MessagingException e) {
                                    retryCount++;
                                    LOG.error(
                                            "Failed to listen to mailbox for user {}. Attempt"
                                                    + " {}/{}. Error: {}",
                                            user.getId(),
                                            retryCount,
                                            MAX_RETRIES,
                                            e.getMessage());

                                    if (retryCount > MAX_RETRIES) {
                                        LOG.error(
                                                "Max retries reached for user {}. Giving up.",
                                                user.getId());
                                        break;
                                    }

                                    try {
                                        Thread.sleep(RETRY_DELAY_MILLIS);
                                    } catch (InterruptedException ie) {
                                        LOG.warn(
                                                "Mailbox listener for user {} was interrupted"
                                                        + " during retry delay.",
                                                user.getId());
                                        Thread.currentThread()
                                                .interrupt(); // Preserve interrupt status
                                        break;
                                    }
                                }
                            }
                        });

        mailboxFutures.put(user.getId(), future);
    }

    void onUserCreated(User createdUser) {
        startMailboxListenerForUser(createdUser);
    }

    void onUserUpdated(User updatedUser) {
        LOG.info("Restarting mailbox listener for user {} due to update.", updatedUser.getId());

        Future<?> future = mailboxFutures.get(updatedUser.getId());
        // TODO mailboxConnectionHandler.closeInbox(); to interrupt the imap idle
        if (future != null) {
            future.cancel(true);
            mailboxFutures.remove(updatedUser.getId());
            startMailboxListenerForUser(updatedUser);
        } else {
            throw new MailboxNotFoundException(updatedUser.getId());
        }
    }
}
