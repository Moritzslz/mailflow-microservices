package de.flowsuite.mailboxservice.message;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;

import de.flowsuite.mailboxservice.exception.FolderException;

import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.springframework.stereotype.Component;

@Component
class FolderUtil {

    private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(FolderUtil.class);

    static IMAPFolder getFolderByAttribute(Store store, String attribute)
            throws MessagingException, FolderException {
        LOG.debug("Getting folder by attribute: {}", attribute);
        IMAPFolder[] folders = (IMAPFolder[]) store.getDefaultFolder().list("*");
        for (IMAPFolder folder : folders) {
            for (String a : folder.getAttributes()) {
                if (attribute.equals(a)) {
                    return folder;
                }
            }
        }
        throw new FolderException(
                String.format("Could not find folder by attribute: %s", attribute), false);
    }

    static IMAPFolder getFolderByName(Store store, String name) throws MessagingException {
        LOG.debug("Getting folder by name: {}", name);
        IMAPFolder folder = (IMAPFolder) store.getFolder(name);
        if (folder.exists()) {
            return folder;
        } else {
            return null;
        }
    }

    static IMAPFolder createFolderByName(Store store, String name)
            throws MessagingException, FolderException {
        LOG.debug("Creating folder: {}", name);
        IMAPFolder folder = (IMAPFolder) store.getFolder(name);
        if (!folder.exists()) {
            if (!folder.create(IMAPFolder.HOLDS_MESSAGES)) {
                throw new FolderException(
                        String.format("Could not create folder %s.", name), false);
            }
            folder.setSubscribed(true);
            return folder;
        } else {
            return folder;
        }
    }

    static void saveMessageToFolder(MimeMessage message, IMAPFolder targetFolder)
            throws MessagingException {
        LOG.debug("Saving message to folder {}.", targetFolder.getFullName());
        if (!targetFolder.isOpen()) {
            targetFolder.open(Folder.READ_WRITE);
        }
        targetFolder.appendMessages(new MimeMessage[] {message});
        LOG.info("Saved message to folder {} successfully.", targetFolder.getFullName());
    }

    public static void moveToFolder(
            IMAPMessage message, IMAPFolder sourceFolder, IMAPFolder targetFolder)
            throws MessagingException, FolderException {
        LOG.debug("Moving message to folder {}.", targetFolder.getFullName());
        if (!targetFolder.exists()) {
            throw new FolderException(
                    String.format("Target folder %s does not exist", targetFolder.getName()),
                    false);
        }
        if (!targetFolder.isOpen()) {
            targetFolder.open(Folder.READ_WRITE);
        }
        if (!sourceFolder.isOpen()) {
            sourceFolder.open(Folder.READ_WRITE);
        }
        sourceFolder.moveMessages(new Message[] {message}, targetFolder);
        LOG.info("Moved original message to {}.", targetFolder.getFullName());
    }
}
