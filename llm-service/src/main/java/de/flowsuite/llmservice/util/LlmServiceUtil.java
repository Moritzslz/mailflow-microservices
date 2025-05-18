package de.flowsuite.llmservice.util;

import de.flowsuite.mailflow.common.entity.MessageCategory;

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

    public static String createHtmlMessage(String reply) {
        return """
               <!DOCTYPE html>
               <html lang="de">
               <head>
                   <meta charset="UTF-8">
                   <meta name="viewport" content="width=device-width, initial-scale=1.0">
               </head>
               <body>
                   {{body}}
               </body>
               </html>
               """
                .replace("{{body}}", reply);
    }
}
