package de.flowsuite.mailboxservice.message;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;

import de.flowsuite.mailboxservice.exception.FolderException;
import de.flowsuite.mailflow.common.entity.User;
import de.flowsuite.mailflow.common.util.AesUtil;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.MessageIDTerm;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Component
class MessageUtil {

    private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(MessageUtil.class);

    private static int compare(IMAPMessage m1, IMAPMessage m2) {
        try {
            Date date1 = m1.getReceivedDate();
            Date date2 = m2.getReceivedDate();
            return Comparator.nullsFirst(Date::compareTo).compare(date1, date2);
        } catch (MessagingException e) {
            LOG.error("Failed to compare messages", e);
            return 0;
        }
    }

    static String getCleanedText(Part p) throws MessagingException, IOException {
        String text = getText(p);
        LOG.debug("Text: {}", text);
        if (text == null || text.isBlank()) {
            LOG.debug("Text is null or blank");
            return "";
        } else {
            LOG.debug("Text: {}", text);
        }
        return text;
    }

    private static String getText(Part p) throws MessagingException, IOException {
        LOG.debug("Extracting text from part");

        if (p == null) {
            return "";
        }

        // Ensure the part is not empty before attempting to retrieve content
        if (p.getSize() == 0) {
            return "";
        }

        // If the part is a text type (plain or HTML), return the content as a String
        if (p.isMimeType("text/plain")) {
            LOG.trace("Part is of MimeType text/plain");

            try {
                Object content = p.getContent();
                return content != null ? removeQuotedLinesFromPlainText(content.toString()) : "";
            } catch (IOException e) {
                return "";
            }
        } else if (p.isMimeType("text/html")) {
            LOG.trace("Part is of MimeType text/html");

            try {
                Object content = p.getContent();
                return content != null ? extractPlainTextFromHtml(content.toString()) : "";
            } catch (IOException e) {
                return "";
            }
        }

        // Handle multipart/alternative
        if (p.isMimeType("multipart/alternative")) {
            LOG.trace("Part is of MimeType multipart/alternative");

            Multipart mp = (Multipart) p.getContent();
            String text = null;

            for (int i = 0; i < mp.getCount(); i++) {
                Part bp = mp.getBodyPart(i);
                if (bp.isMimeType("text/plain")) {
                    if (text == null) {
                        text = getText(bp);
                    }
                    continue;
                } else if (bp.isMimeType("text/html")) {
                    String html = getText(bp); // Extract raw HTML
                    text = extractPlainTextFromHtml(html);
                } else {
                    return getText(bp);
                }
            }
            return text;
        }

        // Handle multipart/mixed - concatenate all text parts
        else if (p.isMimeType("multipart/*")) {
            LOG.trace("Part is of MimeType multipart/*");

            Multipart mp = (Multipart) p.getContent();
            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < mp.getCount(); i++) {
                Part bp = mp.getBodyPart(i);
                String s = getText(bp);
                if (s != null && !s.isEmpty()) {
                    if (!sb.isEmpty()) sb.append(" "); // Add space between parts
                    sb.append(s);
                }
            }
            return sb.toString();
        }

        return "";
    }

    private static String extractPlainTextFromHtml(String html) {
        LOG.debug("Extracting plain text from HTML");

        LOG.debug("HTML: {}", html);

        if (html == null || html.isEmpty()) {
            return null;
        }

        Document doc = Jsoup.parse(html);
        removeQuotedLinesFromHtml(doc);
        return doc.text();
    }

    private static String removeQuotedLinesFromPlainText(String text) {
        LOG.debug("Removing quoted lines from text");
        return text.lines()
                .filter(line -> !line.trim().startsWith(">"))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private static void removeQuotedLinesFromHtml(Document doc) {
        LOG.debug("Removing quoted lines from HTML");
        doc.select("blockquote").remove();
    }

    static List<IMAPMessage> fetchMessageThread(IMAPMessage message, Store store, IMAPFolder inbox)
            throws MessagingException, FolderException {
        LOG.debug("Fetching message thread");
        IMAPFolder sentFolder = FolderUtil.getFolderByAttribute(store, "\\Sent");

        String[] references = message.getHeader("References");
        if (references == null || references.length == 0) {
            LOG.debug("No References header found.");
            return List.of(message);
        }

        List<IMAPMessage> messageThread = new ArrayList<>();
        messageThread.add(message);

        for (String reference : references) {
            String[] messageIds = reference.trim().split("\\s+");
            LOG.debug("messageIds: {}", Arrays.toString(messageIds));

            for (String messageId : messageIds) {
                LOG.debug("Searching for reference: {} in inbox folder", messageId);
                List<IMAPMessage> messages = searchByMessageId(inbox, messageId);
                if (messages != null && !messages.isEmpty()) {
                    messageThread.addAll(messages);
                }
            }
        }

        if (sentFolder != null) {
            sentFolder.open(IMAPFolder.READ_ONLY);

            for (String reference : references) {
                String[] messageIds = reference.trim().split("\\s+");

                for (String messageId : messageIds) {
                    LOG.debug("Searching for reference: {} in sent folder", messageId);
                    List<IMAPMessage> messages = searchByMessageId(inbox, messageId);
                    if (messages != null && !messages.isEmpty()) {
                        messageThread.addAll(messages);
                    }
                }
            }

            sentFolder.close(false);
        }

        messageThread.removeIf(Objects::isNull);

        LOG.debug("Message thread size: {}", messageThread.size());

        // Remove duplicates and sort
        return messageThread.stream()
                .peek(m -> LOG.debug("Sorting debug message: {}", getMessageInfo(m)))
                .distinct()
                .sorted(MessageUtil::compare)
                .collect(Collectors.toList());
    }

    static String buildThreadBody(List<IMAPMessage> messageThread, User user)
            throws MessagingException, IOException {
        LOG.debug("Building thread body");
        StringBuilder threadBody = new StringBuilder();

        String userEmailAddress = AesUtil.decrypt(user.getEmailAddress());

        for (int i = 0; i < messageThread.size(); i++) {
            IMAPMessage message = messageThread.get(i);
            Address[] fromAddresses = message.getFrom();

            boolean isFromUser = false;
            for (Address address : fromAddresses) {
                if (address.toString().equalsIgnoreCase(userEmailAddress)) {
                    isFromUser = true;
                    break;
                }
            }

            if (isFromUser) {
                threadBody
                        .append("\n\n")
                        .append(String.format("Message %d", i + 1))
                        .append("\n")
                        .append("From: Employee (Internal)")
                        .append("\n")
                        .append("Received at: ")
                        .append(message.getReceivedDate())
                        .append("\n")
                        .append("Body:")
                        .append("\n")
                        .append(MessageUtil.getCleanedText(message));
            } else {
                threadBody
                        .append("\n\n")
                        .append(String.format("Message %d", i + 1))
                        .append("\n")
                        .append("From: Customer (External)")
                        .append("\n")
                        .append("Received at: ")
                        .append(message.getReceivedDate())
                        .append("\n")
                        .append("Body:")
                        .append("\n")
                        .append(MessageUtil.getCleanedText(message));
            }
        }

        return threadBody.toString();
    }

    private static String getMessageInfo(IMAPMessage message) {
        if (message == null) {
            return "[null message]";
        }

        try {
            String subject = message.getSubject();
            String messageId = message.getMessageID();
            Date receivedDate = message.getReceivedDate();

            return String.format(
                    "Subject: \"%s\", Message-ID: %s, Date: %s",
                    subject != null ? subject : "(no subject)",
                    messageId != null ? messageId : "(no ID)",
                    receivedDate != null ? receivedDate : "(no date)");
        } catch (MessagingException e) {
            return "[error reading message: " + e.getMessage() + "]";
        }
    }

    static List<IMAPMessage> searchByMessageId(IMAPFolder folder, String messageId)
            throws MessagingException {
        LOG.debug("Searching for email by messageId: {}", messageId);

        MessageIDTerm term = new MessageIDTerm(messageId);
        Message[] messages = folder.search(term);
        LOG.debug("Found {} messages", messages.length);

        if (messages.length == 0) {
            return null;
        }

        return Arrays.stream(messages)
                .filter(IMAPMessage.class::isInstance)
                .map(IMAPMessage.class::cast)
                .toList();
    }

    static String extractFromEmail(IMAPMessage message) throws MessagingException {
        Address[] fromAddresses = message.getFrom();

        if (fromAddresses != null && fromAddresses.length > 0) {
            Address address = fromAddresses[0];
            if (address instanceof InternetAddress) {
                return ((InternetAddress) address).getAddress().toLowerCase();
            } else {
                return address.toString().toLowerCase();
            }
        }

        return null;
    }
}
