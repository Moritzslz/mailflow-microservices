package de.flowsuite.mailboxservice.mailbox;

import static de.flowsuite.mailboxservice.mailbox.MailboxService.TIMEOUT_MS;

import com.sun.mail.imap.IMAPFolder;

import de.flowsuite.mailboxservice.exception.MailboxException;
import de.flowsuite.mailboxservice.exception.MailboxServiceExceptionManager;
import de.flowsuite.mailflow.common.entity.User;

import jakarta.mail.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MailboxListenerTask implements Callable<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(MailboxListenerTask.class);
    static final long DELAY_MS = 1000;

    private final User user;
    private final MailboxConnectionManager mailboxConnectionManager;
    private final MailboxServiceExceptionManager exceptionManager;
    private final boolean shouldDelayStart;
    private final CountDownLatch idleEnteredLatch = new CountDownLatch(1);
    private final AtomicBoolean listenerActive = new AtomicBoolean(false);

    private final AtomicReference<Session> session = new AtomicReference<>(null);
    private final AtomicReference<Store> store = new AtomicReference<>(null);
    private final AtomicReference<IMAPFolder> inbox = new AtomicReference<>(null);

    private final BlockingQueue<Message> messageProcessingQueue = new LinkedBlockingQueue<>();

    MailboxListenerTask(
            User user,
            MailboxConnectionManager mailboxConnectionManager,
            MailboxServiceExceptionManager exceptionManager,
            boolean shouldDelayStart) {
        this.user = user;
        this.mailboxConnectionManager = mailboxConnectionManager;
        this.exceptionManager = exceptionManager;
        this.shouldDelayStart = shouldDelayStart;
    }

    @Override
    public Void call() {
        Thread.currentThread().setName("MailboxListenerTask-User-" + user.getId());

        LOG.debug("Started new thread: {}", Thread.currentThread().getName());

        if (shouldDelayStart) {
            try {
                LOG.debug(
                        "Delaying mailbox listener start for user {} by {} seconds",
                        user.getId(),
                        (double) DELAY_MS / 1000);
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                MailboxException mailboxException =
                        new MailboxException(
                                String.format(
                                        "Mailbox listener thread was interrupted during sleep for"
                                                + " user %d",
                                        user.getId()),
                                e,
                                false);
                exceptionManager.handleException(mailboxException);
                exceptionManager.handleMailboxListenerFailure(user, mailboxException);
            }
        }

        try {
            session.set(mailboxConnectionManager.connectToMailbox(user));
            store.set(mailboxConnectionManager.connectToStore(session.get(), user));
            inbox.set(mailboxConnectionManager.openInbox(store.get(), user.getId()));

            mailboxConnectionManager.addMessageCountListener(
                    inbox.get(), user, messageProcessingQueue);

            listenerActive.set(true);
            mailboxConnectionManager.listenToMailbox(
                    listenerActive,
                    idleEnteredLatch,
                    session.get(),
                    store.get(),
                    inbox.get(),
                    messageProcessingQueue,
                    user);
        } catch (MessagingException | MailboxException e) {
            MailboxException mailboxException =
                    new MailboxException(
                            String.format("Mailbox listener task failed for user %d", user.getId()),
                            e,
                            false);
            exceptionManager.handleException(mailboxException);
            exceptionManager.handleMailboxListenerFailure(user, mailboxException);
        }

        return null;
    }

    boolean hasEnteredImapIdleMode() {
        if (idleEnteredLatch.getCount() == 0 && inbox.get() != null) {
            return true;
        } else {
            try {
                if (shouldDelayStart) {
                    return idleEnteredLatch.await(DELAY_MS + TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } else {
                    return idleEnteredLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    void disconnect() throws MailboxException {
        try {
            listenerActive.set(false);
            if (hasEnteredImapIdleMode()) {
                mailboxConnectionManager.disconnect(inbox.get(), store.get(), user.getId());
            } else {
                throw new MailboxException(
                        String.format(
                                "Failed to disconnect: IMAP IDLE mode was not entered for user %s",
                                user.getId()),
                        true);
            }
        } catch (MessagingException e) {
            throw new MailboxException(
                    String.format("Failed to disconnect user %s", user.getId()), e, false);
        }
    }

    public User getUser() {
        return user;
    }
}
