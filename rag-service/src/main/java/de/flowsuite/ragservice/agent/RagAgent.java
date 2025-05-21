package de.flowsuite.ragservice.agent;

import de.flowsuite.mailflow.common.entity.Customer;
import de.flowsuite.mailflow.common.util.AesUtil;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModelName;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class RagAgent {

    private static final Logger LOG = LoggerFactory.getLogger(RagAgent.class);
    private static final OpenAiEmbeddingModelName MODEL_NAME = OpenAiEmbeddingModelName.TEXT_EMBEDDING_3_SMALL;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_RETRIES = 3;
    private static final int SEGMENT_SIZE_IN_CHARS = 512;
    private static final int SEGMENT_OVERLAP_IN_CHARS = 256;
    private static final int MAX_RESULTS = 3;
    private static final double MIN_SCORE = 0.55;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentSplitter documentSplitter;

    public RagAgent(Customer customer, String database, boolean debug) {
        this.embeddingModel =
                OpenAiEmbeddingModel.builder()
                        .apiKey(AesUtil.decrypt(customer.getOpenaiApiKey()))
                        .modelName(MODEL_NAME)
                        .timeout(TIMEOUT)
                        .maxRetries(MAX_RETRIES)
                        .logRequests(debug)
                        .logResponses(debug)
                        .build();

        this.embeddingStore = PgVectorEmbeddingStore.builder()
                .database(database)
                .table(String.valueOf(customer.getId()))
                .dimension(embeddingModel.dimension())
                .createTable(true)
                .dropTableFirst(true)
                .build();

        this.documentSplitter =
                new DocumentByParagraphSplitter(SEGMENT_SIZE_IN_CHARS, SEGMENT_OVERLAP_IN_CHARS);
    }

    public void embed(long customerId, String text) {
        List<TextSegment> textSegments = documentSplitter.split(Document.from(text));

        LOG.info("Embedding {} text segments for customer {}", textSegments.size(), customerId);

        List<Embedding> embeddings = new ArrayList<>();
        for (TextSegment textSegment : textSegments) {
            embeddings.add(embeddingModel.embed(textSegment).content());
        }
        embeddingStore.addAll(embeddings, textSegments);
    }

    public List<String> search(long customerId, String text) {
        LOG.info("Searching for relevant embeddings for customer {} for query: {}", customerId, text);
        Embedding queryEmbedding = embeddingModel.embed(text).content();

        EmbeddingSearchRequest embeddingSearchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                //.maxResults(3)
                //.minScore(MIN_SCORE)
                .build();

        List<EmbeddingMatch<TextSegment>> relevant = embeddingStore.search(embeddingSearchRequest).matches();

        if (relevant.isEmpty()) {
            LOG.warn("No relevant embeddings found for query: {}", text);
            return null;
        } else {
            LOG.info("Found {} relevant embeddings for query: {}", relevant.size(), text);
        }

        List<String> results = new ArrayList<>();

        for (EmbeddingMatch<TextSegment> embeddingMatch : relevant) {
            LOG.debug("Relevant embedding match score: {}", embeddingMatch.score());
            LOG.debug("Relevant embedding match text: {}", embeddingMatch.embedded().text());
            results.add(embeddingMatch.embedded().text());
        }

        return results;
    }
}
