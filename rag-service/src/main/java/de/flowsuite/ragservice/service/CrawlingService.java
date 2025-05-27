package de.flowsuite.ragservice.service;

import de.flowsuite.mailflow.common.entity.RagUrl;
import de.flowsuite.ragservice.exception.CrawlingException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

@Service
class CrawlingService {

    private static final Logger LOG = LoggerFactory.getLogger(CrawlingService.class);

    CrawlingResult crawl(RagUrl ragUrl) throws CrawlingException {
        LOG.debug("Crawling {} for customer {}", ragUrl.getUrl(), ragUrl.getCustomerId());

        Document doc = null;
        try {
            doc =
                    Jsoup.connect(String.valueOf(URI.create(ragUrl.getUrl())))
                            .followRedirects(true)
                            .get();
        } catch (IOException e) {
            throw new CrawlingException(
                    String.format("Failed to connect to url: %s", ragUrl.getUrl()), e);
        }

        String bodyText = doc.body().text();

        LOG.debug(
                "Scraped {} for customer {}:\n {}",
                ragUrl.getUrl(),
                ragUrl.getCustomerId(),
                bodyText);

        Map<String, String> links =
                doc.select("a[href]").stream()
                        .filter(el -> !el.attr("abs:href").isBlank())
                        .distinct()
                        .collect(
                                Collectors.toMap(
                                        el -> el.attr("abs:href"),
                                        Element::text,
                                        (existing, replacement) ->
                                                existing // handle duplicates by keeping the first
                                        ));

        LOG.debug(
                "Scraped {} links on {} for customer {}",
                links.size(),
                ragUrl.getUrl(),
                ragUrl.getCustomerId());

        if (bodyText.isBlank()) {
            LOG.warn(
                    "The fetched HTML file from {} for customer {} is null or blank.",
                    ragUrl.getUrl(),
                    ragUrl.getCustomerId());
            return null;
        }

        return new CrawlingResult(ragUrl, bodyText, links);
    }
}
