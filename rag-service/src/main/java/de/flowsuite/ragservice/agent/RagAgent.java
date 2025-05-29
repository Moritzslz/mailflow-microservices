package de.flowsuite.ragservice.agent;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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

import javax.sql.DataSource;

public class RagAgent {

    private static final Logger LOG = LoggerFactory.getLogger(RagAgent.class);
    private static final OpenAiEmbeddingModelName MODEL_NAME =
            OpenAiEmbeddingModelName.TEXT_EMBEDDING_3_SMALL;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_RETRIES = 3;
    private static final int SEGMENT_SIZE_IN_CHARS = 512; // TODO increase?
    private static final int SEGMENT_OVERLAP_IN_CHARS = 256;
    private static final int MAX_RESULTS = 3;
    private static final double MIN_SCORE = 0.55;
    private static final String TABLE_PREFIX = "customer_embeddings_";
    private static final String RAG_URL_ID_METADATA_KEY = "ragUrlId";
    private static final String RAG_URL_DESCRIPTION_METADATA_KEY = "description";
    private static final String RAG_URL_LINKS_METADATA_KEY = "links";

    private final Customer customer;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentSplitter documentSplitter;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
                        // .useIndex(true)
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

        List<TextSegment> allTextSegments =
                crawlingResults.stream()
                        .flatMap(result -> prepareTextSegments(result, split).stream())
                        .toList();

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

        if (split) {
            return documentSplitter.split(
                    Document.from(result.bodyText(), Metadata.from(metadata)));
        } else {
            return List.of(TextSegment.from(result.bodyText(), Metadata.from(metadata)));
        }
    }

    private Map<String, Object> prepareMetadata(CrawlingResult result) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(RAG_URL_ID_METADATA_KEY, result.ragUrl().getId());
        metadata.put(RAG_URL_DESCRIPTION_METADATA_KEY, result.ragUrl().getDescription());
        try {
            String linksJson = objectMapper.writeValueAsString(result.links());
            metadata.put(RAG_URL_LINKS_METADATA_KEY, linksJson);
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize links metadata", e);
        }
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
        Filter filter = metadataKey(RAG_URL_ID_METADATA_KEY).isEqualTo(ragUrlId);
        embeddingStore.removeAll(filter);
        LOG.info("Removed all rag url {} embeddings for customer {}", ragUrlId, customer.getId());
    }

    public Optional<RagServiceResponse> search(String text) {
        LOG.info(
                "Searching for relevant embeddings for customer {} for query: {}",
                customer.getId(),
                text);
        Embedding queryEmbedding = embeddingModel.embed(text).content();

        EmbeddingSearchRequest embeddingSearchRequest =
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        // .maxResults(MAX_RESULTS)
                        // .minScore(MIN_SCORE)
                        .build();

        List<EmbeddingMatch<TextSegment>> relevant =
                embeddingStore.search(embeddingSearchRequest).matches();

        if (relevant.isEmpty()) {
            LOG.warn("No relevant embeddings found for query: {}", text);
            return Optional.empty();
        } else {
            LOG.info("Found {} relevant embeddings for query: {}", relevant.size(), text);
        }

        // Sort by score descending
        relevant.sort(Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score).reversed());

        List<String> relevantSegments = new ArrayList<>();
        List<String> relevantMetadata = new ArrayList<>();
        List<Double> scores = new ArrayList<>();

        for (EmbeddingMatch<TextSegment> embeddingMatch : relevant) {
            LOG.debug("Embedding match score: {}", embeddingMatch.score());
            LOG.debug("Embedding match text:\n{}", embeddingMatch.embedded().text());
            LOG.debug("Embedding match metadata:\n{}", embeddingMatch.embedded().metadata());
            relevantSegments.add(embeddingMatch.embedded().text());
            relevantMetadata.add(embeddingMatch.embedded().metadata().toString());
            scores.add(embeddingMatch.score());
        }

        return Optional.of(new RagServiceResponse(relevantSegments, relevantMetadata, scores));
    }
}
