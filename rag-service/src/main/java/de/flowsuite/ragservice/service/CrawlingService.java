package de.flowsuite.ragservice.service;

import de.flowsuite.mailflow.common.entity.RagUrl;
import de.flowsuite.ragservice.common.CrawlingResult;
import de.flowsuite.ragservice.exception.CrawlingException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;

@Service
class CrawlingService {

    private static final Logger LOG = LoggerFactory.getLogger(CrawlingService.class);

    CrawlingResult crawl(RagUrl ragUrl) throws CrawlingException {
        LOG.debug("Crawling {} for customer {}", ragUrl.getUrl(), ragUrl.getCustomerId());

        URI uri = URI.create(ragUrl.getUrl());

        Document doc;
        try {
            doc = Jsoup.connect(String.valueOf(uri)).get();
        } catch (IOException e) {
            throw new CrawlingException(
                    String.format("Failed to connect to url: %s", ragUrl.getUrl()), e);
        }

        String bodyText = doc.body().text();

        LOG.debug(
                "Scraped rag url {} for customer {}. ({})",
                ragUrl.getId(),
                ragUrl.getCustomerId(),
                ragUrl.getUrl());

        HashMap<String, String> relevantLinks = extractRelevantLinks(doc, uri.getHost());

        LOG.debug(
                "Scraped {} unique links on rag url {} for customer {}",
                relevantLinks.size(),
                ragUrl.getId(),
                ragUrl.getCustomerId());

        if (bodyText.isBlank()) {
            throw new CrawlingException(String.format("Failed to crawl rag url %d for customer %d: Body text is null or blank. (%s)", ragUrl.getId(),
                    ragUrl.getCustomerId(),
                    ragUrl.getUrl()));
        }

        return new CrawlingResult(ragUrl, bodyText, relevantLinks);
    }

    private static HashMap<String, String> extractRelevantLinks(Document doc, String host) {
        String rootDomain = host;
        String[] parts = host.split("\\.");
        if (parts.length >= 2) {
            rootDomain = parts[parts.length - 2] + "." + parts[parts.length - 1];
        }

        HashMap<String, String> uniqueLinks = new HashMap<>();
        Elements links = doc.select("a");
        for (Element link : links) {
            String text = link.text();
            String url = link.attr("href");
            if (!isRelevant(url, rootDomain)) continue;
            uniqueLinks.put(text, url);
        }

        return uniqueLinks;
    }

    private static boolean isRelevant(String url, String rootDomain) {
        if (url.isEmpty()) return false;
        if (url.startsWith("mailto:") || url.startsWith("javascript:")) return false;
        return url.contains(rootDomain);
    }
}
