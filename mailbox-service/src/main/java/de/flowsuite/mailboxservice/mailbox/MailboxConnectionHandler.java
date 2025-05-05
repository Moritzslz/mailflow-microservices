package de.flowsuite.mailboxservice.mailbox;

import com.sun.mail.imap.IMAPFolder;

import de.flowsuite.mailboxservice.exception.MailboxException;
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

@Service
class MailboxConnectionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MailboxConnectionHandler.class);

    private final boolean isDebug;

    public MailboxConnectionHandler(@Value("${mail.debug}") boolean isDebug) {
        this.isDebug = isDebug;
    }

    Store connectToMailbox(User user) throws MailboxException {
        LOG.debug("Connecting to mailbox of user {}", user.getId());

        Settings settings = user.getSettings();
        Properties properties = getProperties(settings);

        return createStoreSession(properties, user);
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

        return properties;
    }

    private Store createStoreSession(Properties properties, User user) throws MailboxException {
        LOG.debug("Creating store session for user {}", user.getId());

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
            throw new MailboxException(
                    String.format("Failed to create store session for user %d", user.getId()),
                    e,
                    true);
        }
    }

    IMAPFolder openInbox(Store store) throws MailboxException {
        LOG.debug("Opening INBOX folder.");

        try (IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX")) {
            inbox.open(Folder.READ_WRITE);
            return inbox;
        } catch (MessagingException e) {
            throw new MailboxException("Failed to open inbox", e, true);
        }
    }

    void listenToMailbox(User user, IMAPFolder inbox) throws MailboxException {
        if (!inbox.isOpen()) {
            try {
                inbox.open(Folder.READ_WRITE);
            } catch (MessagingException e) {
                throw new MailboxException("Failed to open inbox", e, true);
            }
        }

        addMessageCountListener(inbox, user);

        LOG.debug("Issuing IMAP idle command for mailbox of user {}", user.getId());

        try {
            inbox.idle();
        } catch (MessagingException e) {
            throw new MailboxException("Failed to enter IDLE mode", e, true);
        }
    }

    private void addMessageCountListener(IMAPFolder inbox, User user) {
        LOG.debug("Adding message count listener for user {}.", user.getId());
        inbox.addMessageCountListener(
                new MessageCountAdapter() {
                    @Override
                    public void messagesAdded(MessageCountEvent messageCountEvent) {
                        Message[] messages = messageCountEvent.getMessages();

                        LOG.info(
                                "User {} received {} new message(s)",
                                user.getId(),
                                messages.length);

                        for (Message message : messages) {
                            // TODO process message
                            continue;
                        }
                    }
                });
    }

    void closeConnection(IMAPFolder inbox, Store store) throws MailboxException {
        // Close inbox to terminate idle
        closeInbox(inbox);

        try {
            if (store.isConnected()) {
                LOG.debug("Closing store session");
                store.close();
            }
        } catch (MessagingException e) {
            throw new MailboxException("Failed to close store", e, false);
        }
    }

    private void closeInbox(IMAPFolder inbox) throws MailboxException {
        LOG.debug("Closing inbox folder");

        try {
            if (!inbox.isOpen()) {
                LOG.debug("Inbox folder is already closed");
            } else {
                inbox.close(false);
            }
        } catch (MessagingException e) {
            throw new MailboxException("Failed to close inbox", e, false);
        }
    }
}
