package de.flowsuite.mailboxservice.mailbox;

import com.sun.mail.imap.IMAPFolder;

import de.flowsuite.mailboxservice.exception.MailboxException;
import de.flowsuite.mailboxservice.message.MessageService;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
class MailboxConnectionManager {

    private static final Logger LOG = LoggerFactory.getLogger(MailboxConnectionManager.class);

    private final boolean debug;
    private final MessageService messageService;

    MailboxConnectionManager(@Value("${mail.debug}") boolean debug, MessageService messageService) {
        this.debug = debug;
        this.messageService = messageService;
    }

    Session connectToMailbox(User user) throws MailboxException {
        LOG.debug("Connecting to mailbox of user {}", user.getId());

        Settings settings = user.getSettings();
        Properties properties = getProperties(settings);

        Session session = Session.getInstance(properties);
        session.setDebug(debug);
        return session;
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
        properties.put("mail.imap.peek", "true");

        return properties;
    }

    Store connectToStore(Session session, User user) throws MessagingException {
        LOG.debug("Connecting to store for user {}", user.getId());

        Store store = session.getStore("imaps");

        Settings settings = user.getSettings();

        store.connect(
                settings.getImapHost(),
                AesUtil.decrypt(user.getEmailAddress()),
                AesUtil.decrypt(settings.getMailboxPassword()));

        return store;
    }

    Transport connectToTransport(Session session, User user) throws MessagingException {
        LOG.debug("Connecting to transport for user {}", user.getId());

        Transport transport = session.getTransport("smtps");

        Settings settings = user.getSettings();

        transport.connect(
                settings.getSmtpHost(),
                AesUtil.decrypt(user.getEmailAddress()),
                AesUtil.decrypt(settings.getMailboxPassword()));

        return transport;
    }

    IMAPFolder openInbox(Store store, long userId) throws MessagingException, MailboxException {
        LOG.debug("Opening INBOX folder of user {}", userId);

        IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX");

        if (!inbox.exists()) {
            throw new MailboxException(
                    String.format("INBOX folder of user %d does not exist", userId), true);
        }

        inbox.open(Folder.READ_WRITE);
        return inbox;
    }

    void listenToMailbox(
            AtomicBoolean listenerActive,
            CountDownLatch idleEnteredLatch,
            Session session,
            Store store,
            IMAPFolder inbox,
            BlockingQueue<Message> messageProcessingQueue,
            User user)
            throws MessagingException {
        // Automatically reenter IDLE mode after the connection is closed if listener is active
        while (listenerActive.get() && !Thread.currentThread().isInterrupted()) {
            try {
                LOG.debug(
                        "Attempting to monitor inbox and manage IDLE mode for user {}.",
                        user.getId());

                if (!inbox.isOpen()) {
                    LOG.debug("Inbox of user {} is closed, reopening...", user.getId());
                    inbox.open(Folder.READ_WRITE);
                }

                LOG.info("Entering IDLE mode for mailbox of user {}", user.getId());
                idleEnteredLatch.countDown();
                inbox.idle();
                LOG.info("Exiting IDLE mode for mailbox of user {}", user.getId());

                // Process messages using individual MailboxListenerTask thread
                List<Message> messages = new ArrayList<>();
                messageProcessingQueue.drainTo(messages);
                processMessages(messages, session, store, inbox, user);

                // Trigger the message count listener to detect new messages and queue them for
                // processing.
                inbox.getMessageCount();
            } catch (FolderClosedException e) {
                LOG.info(
                        "Server closed IMAP connection for user {}. Reason: {}. Trying to reconnect"
                                + " and reenter IDLE mode...",
                        user.getId(),
                        e.getMessage());
            }
        }
        LOG.info(
                "Mailbox listener for user {} is stopping. Listener active: {},"
                        + " Thread interrupted: {}",
                user.getId(),
                listenerActive.get(),
                Thread.currentThread().isInterrupted());
    }

    void addMessageCountListener(
            IMAPFolder inbox, User user, BlockingQueue<Message> messageProcessingQueue) {
        LOG.debug("Adding message count listener to inbox of user {}", user.getId());
        inbox.addMessageCountListener(
                new MessageCountAdapter() {
                    @Override
                    public void messagesAdded(MessageCountEvent messageCountEvent) {
                        Message[] messages = messageCountEvent.getMessages();

                        LOG.info(
                                "User {} received {} new message(s)",
                                user.getId(),
                                messages.length);

                        // Queue messages for processing
                        Collections.addAll(messageProcessingQueue, messages);

                        try {
                            inbox.getMessageCount(); // Abort IDLE mode
                        } catch (MessagingException e) {
                            LOG.error("Failed to abort IDLE mode for user {}", user.getId(), e);
                        }
                    }
                });
    }

    void disconnect(IMAPFolder inbox, Store store, long userId) throws MessagingException {
        LOG.debug("Disconnecting mailbox listener of user {}...", userId);

        // Any operation performed on the open inbox will cause the inbox to exit IDLE mode,
        // resulting in the .idle() method returning.
        if (inbox.isOpen()) {
            inbox.getMessageCount();
        }

        if (inbox.isOpen()) {
            inbox.close(false);
        }
        store.close();
    }

    private void processMessages(
            List<Message> messages, Session session, Store store, IMAPFolder inbox, User user)
            throws MessagingException {
        LOG.debug("Starting to process {} messages for user {}", messages.size(), user.getId());

        Transport transport = null;
        if (user.getSettings().isAutoReplyEnabled()) {
            transport = connectToTransport(session, user);
        }

        for (Message message : messages) {
            messageService.processMessage(message, store, transport, inbox, user);
        }

        if (transport != null) {
            transport.close();
        }
    }
}
