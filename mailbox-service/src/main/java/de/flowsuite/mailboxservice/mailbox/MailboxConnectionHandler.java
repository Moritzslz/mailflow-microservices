package de.flowsuite.mailboxservice.mailbox;

import com.sun.mail.imap.IMAPFolder;

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

    void listenToMailbox(User user) throws MessagingException {
        Settings settings = user.getSettings();
        Properties properties = getProperties(settings);

        Store store = createStoreSession(properties, user);
        IMAPFolder inbox = openInbox(store);

        addMessageCountListener(inbox, user);

        LOG.debug("Issuing IMAP idle command for mailbox of user {}.", user.getId());

        inbox.idle();
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

    private Store createStoreSession(Properties properties, User user) {
        LOG.debug("Connecting to mailbox of user {}.", user.getId());

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
            LOG.error(
                    "Failed to connect to mailbox of user {}. Error: {}",
                    user.getId(),
                    e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private IMAPFolder openInbox(Store store) {
        LOG.debug("Opening INBOX folder.");

        try {
            IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);
            return inbox;
        } catch (MessagingException e) {
            LOG.error("Failed to open inbox. Error: {}", e.getMessage());
            throw new RuntimeException(e);
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
                                "User {} received {} new message(s).",
                                user.getId(),
                                messages.length);

                        for (Message message : messages) {
                            // TODO process message
                            continue;
                        }
                    }
                });
    }

    void closeInbox(IMAPFolder inbox) {
        LOG.debug("Closing INBOX folder.");

        try {
            inbox.close(false);
        } catch (MessagingException e) {
            LOG.error("Failed to close inbox. Error: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
