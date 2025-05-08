package de.flowsuite.mailboxservice.util;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class MessageUtil {

    private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(MessageUtil.class);

    public static String getText(Part p) throws MessagingException, IOException {
        LOG.debug("Extracting text from part.");

        if (p == null) {
            return null;
        }

        // Ensure the part is not empty before attempting to retrieve content
        if (p.getSize() == 0) {
            return null;
        }

        // If the part is a text type (plain or HTML), return the content as a String
        if (p.isMimeType("text/plain")) {
            LOG.debug("Part is of MimeType text/plain.");

            try {
                Object content = p.getContent();
                return content != null ? content.toString() : "";
            } catch (IOException e) {
                return null;
            }
        } else if (p.isMimeType("text/html")) {
            LOG.debug("Part is of MimeType text/html.");

            try {
                Object content = p.getContent();
                return content != null ? extractPlainTextFromHtml(content.toString()) : null;
            } catch (IOException e) {
                return null;
            }
        }

        // Handle multipart/alternative
        if (p.isMimeType("multipart/alternative")) {
            LOG.debug("Part is of MimeType multipart/alternative.");

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
            LOG.debug("Part is of MimeType multipart/*.");

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

        return null;
    }

    private static String extractPlainTextFromHtml(String html) {
        LOG.debug("Extracting plain text from HTML.");

        if (html == null || html.isEmpty()) {
            return null;
        }

        Document doc = Jsoup.parse(html);
        return doc.text(); // Extract readable plain text
    }
}
