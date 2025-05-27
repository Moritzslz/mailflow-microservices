package de.flowsuite.llmservice.util;

import de.flowsuite.mailflow.common.entity.Customer;
import de.flowsuite.mailflow.common.entity.MessageCategory;
import de.flowsuite.mailflow.common.entity.User;
import de.flowsuite.mailflow.common.util.AesUtil;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.net.URL;
import java.util.List;

public class LlmServiceUtil {

    public static String formatCategories(List<MessageCategory> categories) {
        StringBuilder formattedCategories = new StringBuilder();

        for (MessageCategory category : categories) {
            formattedCategories
                    .append("- Category: ")
                    .append(category.getCategory())
                    .append("\n")
                    .append("  Description: ")
                    .append(category.getDescription())
                    .append("\n\n");
        }

        return formattedCategories.toString();
    }

    public static boolean isValidHtmlBody(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }

        input = input.trim();

        String lowerInput = input.toLowerCase();
        if (lowerInput.startsWith("<html")
                || lowerInput.contains("<head")
                || lowerInput.contains("<body")) {
            return false;
        }

        try {
            Document document = Jsoup.parse(input);
            return !document.body().children().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // spotless:off
    public static String createHtmlMessage(String reply, User user, Customer customer, URL url) {
        StringBuilder htmlBuilder = new StringBuilder();

        // HTML structure
        htmlBuilder.append("<!DOCTYPE html>")
                .append("<html lang=\"de\">")
                .append("<head>")
                .append("<meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
                .append("</head>")
                .append("<body>");

        // Body content
        htmlBuilder.append(reply)
                .append("<br/><br/>")
                .append("<p>Mit freundlichen Grüßen, </p>")
                .append("<p>").append(AesUtil.decrypt(user.getFirstName())).append(" ").append(AesUtil.decrypt(user.getLastName())).append("</p>");

                if (user.getPosition() != null) {
                htmlBuilder.append("<p>").append(user.getPosition()).append("</p>");
                }

                htmlBuilder.append("<p>").append(AesUtil.decrypt(user.getEmailAddress()));

                if (user.getPhoneNumber() != null) {
                    htmlBuilder.append(" | ").append(AesUtil.decrypt(user.getPhoneNumber())).append("</p>");
                }

                htmlBuilder.append("<br/><br/>")
                .append("<p>").append(customer.getCompany()).append("</p>")
                .append("<p>").append(customer.getStreet()).append(" ").append(customer.getHouseNumber()).append("</p>")
                .append("<p>").append(customer.getPostalCode()).append(" ").append(customer.getCity()).append("</p>");

        // If URL is provided (for response rating)
        if (url != null) {
            htmlBuilder.append("<br/><br/>")
                    .append("<a href=\"").append(url).append("\" target=\"_blank\" rel=\"noopener noreferrer\">Wie hilfreich war diese Antwort?</a>");
        }

        htmlBuilder.append("</body>")
                .append("</html>");

        return htmlBuilder.toString();
    }
    // spotless:on

}
