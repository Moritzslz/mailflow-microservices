package de.flowsuite.mailboxservice.message;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;
import com.sun.mail.smtp.SMTPSendFailedException;

import de.flowsuite.mailboxservice.exception.FolderException;
import de.flowsuite.mailboxservice.exception.ProcessingException;
import de.flowsuite.mailflow.common.entity.User;
import de.flowsuite.mailflow.common.util.AesUtil;

import jakarta.mail.Flags;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class MessageReplyHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MessageReplyHandler.class);
    private static final String MANUAL_REVIEW_FOLDER_NAME = "_Manual Review";
    static final long DELAY_MILLISECONDS = 3000;

    void handleReply(
            User user,
            IMAPMessage originalMessage,
            String reply,
            Store store,
            Transport transport,
            IMAPFolder inbox)
            throws MessagingException, ProcessingException {
        LOG.debug("Handling reply for user {}", user.getId());

        if (reply == null || reply.isBlank()) {
            moveToManualReviewFolder(user, originalMessage, store, inbox);
        } else {
            String userEmailAddress = AesUtil.decrypt(user.getEmailAddress());
            MimeMessage replyMessage = createReplyMessage(userEmailAddress, originalMessage, reply);

            if (user.getSettings().isAutoReplyEnabled()) {
                sendReply(replyMessage, store, transport, user.getId());
                originalMessage.setFlag(Flags.Flag.ANSWERED, true);
            } else {
                saveDraft(replyMessage, store, user.getId());
            }
        }
    }

    private void moveToManualReviewFolder(
            User user, IMAPMessage originalMessage, Store store, IMAPFolder inbox)
            throws MessagingException, FolderException {
        LOG.debug("Moving original message to attention required folder for user {}", user.getId());

        IMAPFolder actionRequiredFolder =
                FolderUtil.getFolderByName(store, MANUAL_REVIEW_FOLDER_NAME);
        if (actionRequiredFolder == null) {
            actionRequiredFolder = FolderUtil.createFolderByName(store, MANUAL_REVIEW_FOLDER_NAME);
        }
        FolderUtil.moveToFolder(originalMessage, inbox, actionRequiredFolder);
        LOG.info(
                "Moved original message successfully to attention required folder for user {}",
                user.getId());
    }

    private void saveDraft(MimeMessage draftMessage, Store store, long userId)
            throws MessagingException, FolderException {
        LOG.debug("Saving draft for user {}", userId);

        draftMessage.setFlags(new Flags(Flags.Flag.DRAFT), true);
        IMAPFolder draftsFolder = FolderUtil.getFolderByAttribute(store, "\\Drafts");
        FolderUtil.saveMessageToFolder(draftMessage, draftsFolder);

        LOG.info("Draft saved successfully for user {}", userId);
    }

    private void sendReply(MimeMessage replyMessage, Store store, Transport transport, long userId)
            throws MessagingException, ProcessingException {
        LOG.debug("Sending response for user {}", userId);

        try {
            transport.sendMessage(replyMessage, replyMessage.getAllRecipients());
        } catch (SMTPSendFailedException e) {
            int returnCode = e.getReturnCode();
            if (returnCode >= 400 && returnCode < 500) {
                LOG.error(
                        "{} response code received from SMTP server. Retrying once in {} seconds",
                        returnCode,
                        (double) DELAY_MILLISECONDS / 1000);
                try {
                    Thread.sleep(DELAY_MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                transport.sendMessage(replyMessage, replyMessage.getAllRecipients());
            } else {
                throw new ProcessingException("Failed to send response", e, false);
            }
        }

        IMAPFolder sentFolder = FolderUtil.getFolderByAttribute(store, "\\Sent");
        FolderUtil.saveMessageToFolder(replyMessage, sentFolder);

        LOG.info("Response sent successfully for user {}", userId);
    }

    private MimeMessage createReplyMessage(
            String userEmailAddress, IMAPMessage originalMessage, String body)
            throws MessagingException, ProcessingException {
        LOG.debug("Creating response message");

        if (originalMessage == null) {
            throw new ProcessingException("Original message cannot be null", false);
        }

        MimeMessage replyMessage = (MimeMessage) originalMessage.reply(true);
        replyMessage.setFrom(new InternetAddress(userEmailAddress));
        replyMessage.setContent(body, "text/html; charset=UTF-8");
        return replyMessage;
    }
}
