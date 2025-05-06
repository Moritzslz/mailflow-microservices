package de.flowsuite.mailboxservice.mailbox;

import com.sun.mail.imap.IMAPFolder;

import de.flowsuite.mailboxservice.exception.MailboxConnectionException;
import de.flowsuite.mailflow.common.entity.Settings;
import de.flowsuite.mailflow.common.entity.User;
import de.flowsuite.mailflow.common.util.AesUtil;

import jakarta.mail.*;
import jakarta.mail.event.MessageCountAdapter;
import jakarta.mail.event.MessageCountEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
class MailboxConnectionManager {

    private static final Logger LOG = LoggerFactory.getLogger(MailboxConnectionManager.class);

    private final boolean isDebug;

    MailboxConnectionManager(@Value("${mail.debug}") boolean isDebug) {
        this.isDebug = isDebug;
    }

    IMAPFolder connectToMailbox(User user) throws MailboxConnectionException {
        LOG.debug("Connecting to mailbox of user {}", user.getId());

        Settings settings = user.getSettings();
        Properties properties = getProperties(settings);

        Store store = connectToStore(properties, user);

        return openInbox(store, user.getId());
    }

    private static Properties getProperties(Settings settings) {
        Properties properties = new Properties();
        properties.put("mail.store.protocol", "imap");

        // SMTP properties
        properties.put("mail.smtp.host", settings.getSmtpHost());
        properties.put("mail.smtp.port", settings.getSmtpPort());
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.ssl.enable", "true");
        properties.put("mail.smtp.starttls.enable", "true");

        // IMAP properties
        properties.put("mail.imap.host", settings.getImapHost());
        properties.put("mail.imap.port", settings.getImapPort());
        properties.put("mail.imap.auth", "true");
        properties.put("mail.imap.ssl.enable", "true");
        properties.put("mail.imap.starttls.enable", "true");
        properties.put("mail.imap.connectiontimeout", "15000");
        properties.put("mail.imap.timeout", "30000");
        properties.put("mail.imap.writetimeout", "15000");
        properties.put("mail.imap.connectionpoolsize", "2"); // To fix reconnection bug

        return properties;
    }

    private Store connectToStore(Properties properties, User user)
            throws MailboxConnectionException {
        LOG.debug("Connecting to store for user {}", user.getId());

        try {
            Session session = Session.getInstance(properties);
            session.setDebug(isDebug);
            Store store = session.getStore("imap");

            Settings settings = user.getSettings();

            store.connect(
                    settings.getImapHost(),
                    AesUtil.decrypt(user.getEmailAddress()),
                    AesUtil.decrypt(settings.getMailboxPassword()));

            return store;
        } catch (MessagingException e) {
            throw new MailboxConnectionException(
                    String.format("Failed to create store session for user %d", user.getId()),
                    e,
                    true);
        }
    }

    private IMAPFolder openInbox(Store store, long userId) throws MailboxConnectionException {
        LOG.debug("Opening INBOX folder of user {}", userId);

        try {
            IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX");

            if (!inbox.exists()) {
                throw new MailboxConnectionException(
                        String.format("INBOX folder of user %d does not exist", userId), true);
            }

            inbox.open(Folder.READ_WRITE);
            return inbox;
        } catch (MessagingException e) {
            throw new MailboxConnectionException(
                    String.format("Failed to open inbox of user %d", userId), e, true);
        }
    }

    void listenToMailbox(
            AtomicBoolean listenerActive,
            CountDownLatch idleEnteredLatch,
            IMAPFolder inbox,
            long userId)
            throws MailboxConnectionException {
        // Automatically reenter IDLE mode after the connection is closed if listener is active
        while (listenerActive.get() && !Thread.currentThread().isInterrupted()) {
            try {
                LOG.debug("Attempting to monitor inbox and manage IDLE mode for user {}.", userId);

                if (!inbox.isOpen()) {
                    LOG.debug("Inbox of user {} is closed, reopening...", userId);
                    inbox.open(Folder.READ_WRITE);
                }

                LOG.info("Entering IDLE mode for mailbox of user {}", userId);
                idleEnteredLatch.countDown();
                inbox.idle();
                LOG.info("Exiting IDLE mode for mailbox of user {}", userId);
            } catch (FolderClosedException e) {
                LOG.info(
                        "Server closed IMAP connection for user {}. Reason: {}. Trying to reconnect and reenter IDLE mode...",
                        userId,
                        e.getMessage());
            } catch (MessagingException e) {
                throw new MailboxConnectionException(
                        String.format(
                                "Unexpected messaging exception while managing IDLE mode for user"
                                        + " %s. Aborting listener loop.",
                                userId),
                        e,
                        true);
            }
        }
        LOG.info(
                "Mailbox listener for user {} is stopping. Listener active: {},"
                        + " Thread interrupted: {}",
                userId,
                listenerActive.get(),
                Thread.currentThread().isInterrupted());
    }

    void addMessageCountListener(IMAPFolder inbox, long userId) {
        LOG.debug("Adding message count listener to inbox of user {}", userId);
        inbox.addMessageCountListener(
                new MessageCountAdapter() {
                    @Override
                    public void messagesAdded(MessageCountEvent messageCountEvent) {
                        Message[] messages = messageCountEvent.getMessages();

                        LOG.info("User {} received {} new message(s)", userId, messages.length);

                        for (Message message : messages) {
                            // TODO process message
                            continue;
                        }
                    }
                });
    }

    void disconnect(IMAPFolder inbox, long userId) throws MailboxConnectionException {
        LOG.debug("Disconnecting mailbox listener of user {}...", userId);

        // Any operation performed on the open inbox will cause the inbox to exit IDLE mode,
        // resulting in the .idle() method returning.
        if (inbox.isOpen()) {
            try {
                inbox.getMessageCount();
            } catch (MessagingException e) {
                throw new MailboxConnectionException(
                        String.format("Failed to get message count for user %d", userId), e, false);
            }
        }

        try {
            if (inbox.isOpen()) {
                inbox.close(false);
            }
            Store store = inbox.getStore();
            store.close();
        } catch (MessagingException e) {
            throw new MailboxConnectionException(
                    String.format("Failed to disconnect user %d", userId), e, false);
        }
    }
}
