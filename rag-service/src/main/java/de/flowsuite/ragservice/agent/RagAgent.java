package de.flowsuite.ragservice.agent;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import de.flowsuite.mailflow.common.dto.RagServiceResponse;
import de.flowsuite.mailflow.common.entity.Customer;
import de.flowsuite.mailflow.common.util.AesUtil;
import de.flowsuite.ragservice.common.CrawlingResult;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModelName;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.pgvector.DefaultMetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

import javax.sql.DataSource;

public class RagAgent {

    private static final Logger LOG = LoggerFactory.getLogger(RagAgent.class);
    private static final OpenAiEmbeddingModelName MODEL_NAME =
            OpenAiEmbeddingModelName.TEXT_EMBEDDING_3_SMALL;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_RETRIES = 3;
    private static final int SEGMENT_SIZE_IN_CHARS = 512; // TODO increase?
    private static final int SEGMENT_OVERLAP_IN_CHARS = 256;
    private static final int MAX_RESULTS = 5;
    private static final double MIN_SCORE = 0.65;
    private static final String TABLE_PREFIX = "customer_embeddings_";
    private static final String ID_METADATA_KEY = "ragUrlId";
    private static final String DESCRIPTION_METADATA_KEY = "description";
    private static final String SOURCE_URL_METADATA_KEY = "sourceUrl";

    private final Customer customer;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentSplitter documentSplitter;

    public RagAgent(Customer customer, DataSource dataSource, boolean debug) {
        this.customer = customer;

        this.embeddingModel =
                OpenAiEmbeddingModel.builder()
                        .apiKey(AesUtil.decrypt(customer.getOpenaiApiKey()))
                        .modelName(MODEL_NAME)
                        .timeout(TIMEOUT)
                        .maxRetries(MAX_RETRIES)
                        .logRequests(debug)
                        .logResponses(debug)
                        .build();

        this.embeddingStore =
                PgVectorEmbeddingStore.datasourceBuilder()
                        .datasource(dataSource)
                        .table(TABLE_PREFIX + customer.getId())
                        .dimension(embeddingModel.dimension())
                        .createTable(true)
                        .dropTableFirst(false)
                        // .useIndex(true) TODO: enable?
                        .metadataStorageConfig(DefaultMetadataStorageConfig.defaultConfig())
                        .build();

        this.documentSplitter =
                new DocumentByParagraphSplitter(SEGMENT_SIZE_IN_CHARS, SEGMENT_OVERLAP_IN_CHARS);
    }

    public void embedAll(List<CrawlingResult> crawlingResults) {
        embedAll(crawlingResults, true);
    }

    public void embedAll(List<CrawlingResult> crawlingResults, boolean split) {
        LOG.info("Embedding {} rag urls for customer {}", crawlingResults.size(), customer.getId());

        List<TextSegment> allTextSegments = new ArrayList<>();
        for (CrawlingResult crawlingResult : crawlingResults) {
            allTextSegments.addAll(prepareTextSegments(crawlingResult, split));
        }

        storeEmbeddings(allTextSegments);
    }

    public void embed(CrawlingResult crawlingResult) {
        embed(crawlingResult, true);
    }

    public void embed(CrawlingResult crawlingResult, boolean split) {
        LOG.info(
                "Embedding rag url {} for customer {}",
                crawlingResult.ragUrl().getId(),
                customer.getId());

        List<TextSegment> textSegments = prepareTextSegments(crawlingResult, split);
        storeEmbeddings(textSegments);
    }

    private List<TextSegment> prepareTextSegments(CrawlingResult result, boolean split) {
        Map<String, Object> metadata = prepareMetadata(result);
        LOG.debug("Embedding metadata: {}", metadata);

        List<TextSegment> bodySegments;
        List<TextSegment> linksSegments = new ArrayList<>();

        if (split) {
            bodySegments =
                    documentSplitter.split(
                            Document.from(result.bodyText(), Metadata.from(metadata)));
        } else {
            bodySegments = List.of(TextSegment.from(result.bodyText(), Metadata.from(metadata)));
        }

        for (Map.Entry<String, String> link : result.links().entrySet()) {
            linksSegments.add(
                    TextSegment.from(
                            "Link: " + link.getKey() + " " + link.getValue(),
                            Metadata.from(metadata)));
        }

        // Combine and remove duplicates
        List<TextSegment> combined = new ArrayList<>();
        Set<String> seenTexts = new HashSet<>();

        for (TextSegment segment :
                Stream.concat(bodySegments.stream(), linksSegments.stream()).toList()) {
            String normalizedText = segment.text().trim().toLowerCase();
            if (seenTexts.add(normalizedText)) {
                combined.add(segment);
            }
        }

        return combined;
    }

    private Map<String, Object> prepareMetadata(CrawlingResult result) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ID_METADATA_KEY, result.ragUrl().getId());
        metadata.put(SOURCE_URL_METADATA_KEY, result.ragUrl().getUrl());
        metadata.put(DESCRIPTION_METADATA_KEY, result.ragUrl().getDescription());
        return metadata;
    }

    private void storeEmbeddings(List<TextSegment> textSegments) {
        LOG.debug(
                "Storing {} text segment(s) for customer {}",
                textSegments.size(),
                customer.getId());
        List<Embedding> allEmbeddings = embeddingModel.embedAll(textSegments).content();
        embeddingStore.addAll(allEmbeddings, textSegments);
        LOG.info(
                "Embedded {} text segment(s) for customer {}",
                textSegments.size(),
                customer.getId());
    }

    public void removeAllEmbeddings() {
        embeddingStore.removeAll();
    }

    public void removeByRagUrl(long ragUrlId) {
        Filter filter = metadataKey(ID_METADATA_KEY).isEqualTo(ragUrlId);
        embeddingStore.removeAll(filter);
        LOG.info("Removed all embeddings of rag url {} (customer {})", ragUrlId, customer.getId());
    }

    public Optional<RagServiceResponse> search(String text) {
        LOG.info("Searching for relevant embeddings for customer {}", customer.getId());
        Embedding queryEmbedding = embeddingModel.embed(text).content();

        EmbeddingSearchRequest embeddingSearchRequest =
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(MAX_RESULTS)
                        .minScore(MIN_SCORE)
                        .build();

        List<EmbeddingMatch<TextSegment>> matches =
                embeddingStore.search(embeddingSearchRequest).matches();

        if (matches.isEmpty()) {
            LOG.warn("No matches found");
            return Optional.empty();
        } else {
            LOG.info("Found {} matches", matches.size());
        }

        // Sort by score descending
        matches.sort(Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score).reversed());

        // TODO further improve by reranking matches query using LLM

        List<String> relevantSegments = new ArrayList<>();
        List<String> relevantMetadata = new ArrayList<>();
        List<Double> scores = new ArrayList<>();

        for (EmbeddingMatch<TextSegment> embeddingMatch : matches) {
            LOG.debug("Match score: {}", embeddingMatch.score());
            LOG.debug("Match text: {}", embeddingMatch.embedded().text());
            LOG.debug("Match metadata: {}", embeddingMatch.embedded().metadata());
            relevantSegments.add(embeddingMatch.embedded().text());
            relevantMetadata.add(embeddingMatch.embedded().metadata().toString());
            scores.add(embeddingMatch.score());
        }

        return Optional.of(new RagServiceResponse(relevantSegments, relevantMetadata, scores));
    }
}
