package de.flowsuite.mailboxservice.mailbox;

import static de.flowsuite.mailboxservice.mailbox.MailboxService.TIMEOUT_MILLISECONDS;

import com.sun.mail.imap.IMAPFolder;

import de.flowsuite.mailboxservice.exception.MailboxException;
import de.flowsuite.mailflow.common.entity.User;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class MailboxListenerTask implements Callable<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(MailboxListenerTask.class);
    static final long DELAY_MILLISECONDS = 500;

    private final User user;
    private final MailboxConnectionManager mailboxConnectionManager;
    private final MailboxExceptionManager mailboxExceptionManager;
    private final boolean shouldDelayStart;
    private final CountDownLatch idleEnteredLatch = new CountDownLatch(1);
    private final AtomicBoolean listenerActive = new AtomicBoolean(false);

    private final AtomicReference<IMAPFolder> inbox = new AtomicReference<>(null);

    public MailboxListenerTask(
            User user,
            MailboxConnectionManager mailboxConnectionManager,
            MailboxExceptionManager mailboxExceptionManager,
            boolean shouldDelayStart) {
        this.user = user;
        this.mailboxConnectionManager = mailboxConnectionManager;
        this.mailboxExceptionManager = mailboxExceptionManager;
        this.shouldDelayStart = shouldDelayStart;
    }

    @Override
    public Void call() {
        Thread.currentThread().setName("MailboxService-User-" + user.getId());

        LOG.debug("Started new thread: {}", Thread.currentThread().getName());

        if (shouldDelayStart) {
            try {
                LOG.debug(
                        "Delaying mailbox listener start for user {} by {} seconds",
                        user.getId(),
                        (double) DELAY_MILLISECONDS / 1000);
                Thread.sleep(DELAY_MILLISECONDS);
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
                mailboxExceptionManager.handleException(mailboxException);
                mailboxExceptionManager.handleMailboxListenerFailure(user, mailboxException);
            }
        }

        try {
            inbox.set(mailboxConnectionManager.connectToMailbox(user));
            mailboxConnectionManager.addMessageCountListener(inbox.get(), user.getId());
            listenerActive.set(true);
            mailboxConnectionManager.listenToMailbox(
                    listenerActive, idleEnteredLatch, inbox.get(), user.getId());
        } catch (MailboxException e) {
            MailboxException mailboxException =
                    new MailboxException(
                            String.format("Mailbox listener task failed for user %d", user.getId()),
                            e,
                            false);
            mailboxExceptionManager.handleException(mailboxException);
            mailboxExceptionManager.handleMailboxListenerFailure(user, mailboxException);
        }

        return null;
    }

    boolean hasEnteredImapIdleMode() {
        if (idleEnteredLatch.getCount() == 0 && inbox.get() != null) {
            return true;
        } else {
            try {
                if (shouldDelayStart) {
                    return idleEnteredLatch.await(
                            DELAY_MILLISECONDS + TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
                } else {
                    return idleEnteredLatch.await(TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    void disconnect() throws MailboxException {
        if (hasEnteredImapIdleMode()) {
            listenerActive.set(false);
            mailboxConnectionManager.disconnect(inbox.get(), user.getId());
        } else {
            throw new MailboxException("Failed to disconnect.", true);
        }
    }
}
