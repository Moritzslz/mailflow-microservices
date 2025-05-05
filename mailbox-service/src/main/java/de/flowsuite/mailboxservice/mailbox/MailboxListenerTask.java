package de.flowsuite.mailboxservice.mailbox;

import static de.flowsuite.mailboxservice.mailbox.MailboxService.*;

import com.sun.mail.imap.IMAPFolder;

import de.flowsuite.mailboxservice.exception.MailboxException;
import de.flowsuite.mailboxservice.exception.MailboxServiceExceptionHandler;
import de.flowsuite.mailflow.common.entity.User;

import jakarta.mail.Store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class MailboxListenerTask implements Callable<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(MailboxListenerTask.class);

    private final User user;
    private final MailboxConnectionHandler mailboxConnectionHandler;
    private final MailboxServiceExceptionHandler mailboxServiceExceptionHandler;
    private final CountDownLatch storeReady = new CountDownLatch(1);
    private final CountDownLatch inboxReady = new CountDownLatch(1);
    private Store store;
    private IMAPFolder inbox;

    public MailboxListenerTask(
            User user,
            MailboxConnectionHandler mailboxConnectionHandler,
            MailboxServiceExceptionHandler mailboxServiceExceptionHandler) {
        this.user = user;
        this.mailboxConnectionHandler = mailboxConnectionHandler;
        this.mailboxServiceExceptionHandler = mailboxServiceExceptionHandler;
    }

    @Override
    public Void call() {
        try {
            Thread.currentThread().setName("MailboxService-User-" + user.getId());

            LOG.debug("Started new thread: {}", Thread.currentThread().getName());

            int retryCount = 0;

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    this.store = mailboxConnectionHandler.connectToMailbox(user);
                    storeReady.countDown();
                    this.inbox = mailboxConnectionHandler.openInbox(store);
                    inboxReady.countDown();
                    mailboxConnectionHandler.listenToMailbox(user, inbox);
                    break;
                } catch (MailboxException e) {
                    retryCount++;

                    if (retryCount > MAX_RETRIES) {
                        inboxReady.countDown(); // Unblock waiting thread
                        throw new MailboxException(
                                String.format(
                                        "Max retries reached for user %d. Giving up", user.getId()),
                                e,
                                e.shouldNotifyAdmin());
                    }

                    LOG.warn(
                            "Failed to listen to mailbox for user {}. Attempt"
                                    + " {}/{}. Error: {}",
                            user.getId(),
                            retryCount,
                            MAX_RETRIES,
                            e.getMessage());

                    try {
                        Thread.sleep(RETRY_DELAY_MILLIS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        LOG.warn(
                                "Mailbox listener for user {} was interrupted"
                                        + " during retry delay",
                                user.getId());
                        break;
                    }
                }
            }
        } catch (MailboxException e) {
            MailboxException mailboxException =
                    new MailboxException(
                            String.format("Mailbox listener task failed for user %d", user.getId()),
                            e,
                            e.shouldNotifyAdmin());
            mailboxServiceExceptionHandler.handleException(mailboxException);
        }

        return null;
    }

    public Store getStore() throws MailboxException {
        try {
            // wait until the store is ready
            if (storeReady.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                if (store == null) {
                    throw new MailboxException(
                            String.format(
                                    "Getting store of user %s failed. Store is null", user.getId()),
                            true);
                }
                return store;
            } else {
                throw new MailboxException(
                        String.format("Getting store of user %s timed out", user.getId()), true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MailboxException(
                    String.format(
                            "Getting store of user %s was interrupted during wait", user.getId()),
                    true);
        }
    }

    public IMAPFolder getInbox() throws MailboxException {
        try {
            // wait until the inbox is ready
            if (inboxReady.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                if (inbox == null) {
                    throw new MailboxException(
                            String.format(
                                    "Getting inbox of user %s failed. Inbox is null", user.getId()),
                            true);
                }
                return inbox;
            } else {
                throw new MailboxException(
                        String.format("Getting inbox of user %s timed out", user.getId()), true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MailboxException(
                    String.format(
                            "Getting inbox of user %s was interrupted during wait", user.getId()),
                    true);
        }
    }
}
