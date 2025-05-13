package de.flowsuite.mailboxservice.message;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;

import de.flowsuite.mailboxservice.exception.FolderException;
import de.flowsuite.mailboxservice.exception.ProcessingException;
import de.flowsuite.mailflow.common.entity.User;

import jakarta.mail.Flags;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.Transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class MessageResponseHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MessageResponseHandler.class);
    private static final String ATTENTION_REQUIRED_FOLDER_NAME = "Attention Required";

    void handleResponse(
            User user,
            IMAPMessage originalMessage,
            String response,
            Store store,
            Transport transport,
            IMAPFolder inbox)
            throws MessagingException, ProcessingException {
        LOG.debug("Handling response for user {}", user.getId());

        if (response == null || response.isBlank()) {
            moveToAttentionRequiredFolder(user, originalMessage, store, inbox);
        } else {
            handleGeneratedResponse(user, originalMessage, response, store, transport);
        }
    }

    private void moveToAttentionRequiredFolder(
            User user, IMAPMessage originalMessage, Store store, IMAPFolder inbox)
            throws MessagingException, ProcessingException {
        LOG.debug("Moving original message to attention required folder for user {}", user.getId());

        IMAPFolder actionRequiredFolder =
                FolderUtil.getFolderByName(store, ATTENTION_REQUIRED_FOLDER_NAME);
        if (actionRequiredFolder == null) {
            actionRequiredFolder =
                    FolderUtil.createFolderByName(store, ATTENTION_REQUIRED_FOLDER_NAME);
        }
        FolderUtil.moveToFolder(originalMessage, inbox, actionRequiredFolder);
        LOG.info(
                "Moved original message successfully to attention required folder for user {}",
                user.getId());
    }

    private void handleGeneratedResponse(
            User user,
            IMAPMessage originalMessage,
            String response,
            Store store,
            Transport transport)
            throws MessagingException, ProcessingException {
        IMAPMessage responseMessage = createResponseMessage(originalMessage, response);

        if (user.getSettings().isAutoReplyEnabled()) {
            sendResponse(responseMessage, store, transport, user.getId());
            originalMessage.setFlag(Flags.Flag.ANSWERED, true);
        } else {
            saveDraft(responseMessage, store, user.getId());
        }
    }

    private void saveDraft(IMAPMessage responseMessage, Store store, long userId)
            throws MessagingException, FolderException {
        LOG.debug("Saving draft for user {}", userId);
        responseMessage.setFlags(new Flags(Flags.Flag.DRAFT), true);

        IMAPFolder draftsFolder = FolderUtil.getFolderByAttribute(store, "\\Drafts");
        FolderUtil.saveMessageToFolder(responseMessage, draftsFolder);

        LOG.info("Draft saved successfully for user {}", userId);
    }

    private void sendResponse(
            IMAPMessage responseMessage, Store store, Transport transport, long userId)
            throws MessagingException, FolderException {
        LOG.debug("Sending response for user {}", userId);
        transport.sendMessage(responseMessage, responseMessage.getAllRecipients());

        IMAPFolder sentFolder = FolderUtil.getFolderByAttribute(store, "\\Sent");
        FolderUtil.saveMessageToFolder(responseMessage, sentFolder);

        LOG.info("Response sent successfully for user {}", userId);
    }

    private IMAPMessage createResponseMessage(IMAPMessage originalMessage, String body)
            throws ProcessingException, MessagingException {
        LOG.debug("Creating response message");
        if (originalMessage == null) {
            throw new ProcessingException("Original message cannot be null", false);
        }
        // TODO debug
        IMAPMessage responseMessage = (IMAPMessage) originalMessage.reply(false);
        responseMessage.setContent(body, "text/html; charset=UTF-8");
        return responseMessage;
    }
}
