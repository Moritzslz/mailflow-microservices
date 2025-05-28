package de.flowsuite.llmservice.util;

import de.flowsuite.llmservice.exception.InvalidHtmlBodyException;
import de.flowsuite.mailflow.common.dto.CategorisationResponse;
import de.flowsuite.mailflow.common.dto.LlmResponse;
import de.flowsuite.mailflow.common.dto.RagServiceResponse;
import de.flowsuite.mailflow.common.entity.*;
import de.flowsuite.mailflow.common.util.AesUtil;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Optional;

public class LlmServiceUtil {

    private static final Logger LOG = LoggerFactory.getLogger(LlmServiceUtil.class);

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

    public static void validateHtmlBody(String input) throws InvalidHtmlBodyException {
        if (input == null || input.isBlank()) {
            LOG.warn("Input is null or blank");
            throw new InvalidHtmlBodyException("Input is null or blank");
        }

        input = input.trim();

        String lowerInput = input.toLowerCase();
        if (lowerInput.startsWith("<html")
                || lowerInput.contains("<head")
                || lowerInput.contains("<body")) {
            throw new InvalidHtmlBodyException("Input contains html, head or body tag");
        }

        try {
            Document document = Jsoup.parse(input);
            document.body().children();
        } catch (Exception e) {
            throw new InvalidHtmlBodyException("Input is not valid html", e);
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

    public static Optional<CategorisationResponse> validateAndMapCategory(
            LlmResponse response, List<MessageCategory> categories) {
        LOG.debug("Categorisation response: {}", response);

        String category = response.text();

        for (MessageCategory messageCategory : categories) {
            if (messageCategory.getCategory().equalsIgnoreCase(category)) {
                return Optional.of(
                        new CategorisationResponse(
                                messageCategory,
                                response.llmUsed(),
                                response.inputTokens(),
                                response.outputTokens(),
                                response.totalTokens()));
            }
        }

        return Optional.empty();
    }

    public static String formatRagServiceResponse(RagServiceResponse response) {
        StringBuilder context = new StringBuilder();

        List<String> segments = response.relevantSegments();
        List<String> metadata = response.relevantMetadata();

        for (int i = 0; i < segments.size(); i++) {
            context.append("Segment: ")
                    .append(i + 1)
                    .append("\n")
                    .append(segments.get(i))
                    .append("Metadata: ")
                    .append(i + 1)
                    .append("\n")
                    .append(metadata.get(i))
                    .append("\n\n");
        }
        return context.toString();
    }

    public static URL buildRatingUrl(Settings settings, MessageLogEntry entry, String baseUrl)
            throws MalformedURLException, URISyntaxException {

        if (!settings.isResponseRatingEnabled()) {
            return null;
        }

        String query = "token=" + entry.getToken();
        URI uri = new URI(baseUrl + "?" + query);
        return uri.toURL();
    }
}
