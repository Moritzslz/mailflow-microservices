package de.flowsuite.ragservice.service;

import static de.flowsuite.mailflow.common.util.Util.BERLIN_ZONE;

import de.flowsuite.mailflow.common.entity.Customer;
import de.flowsuite.mailflow.common.entity.RagUrl;
import de.flowsuite.mailflow.common.exception.IdConflictException;
import de.flowsuite.ragservice.agent.RagAgent;
import de.flowsuite.shared.exception.ExceptionManager;
import de.flowsuite.shared.exception.ServiceException;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

@Service
public class RagService {

    private static final Logger LOG = LoggerFactory.getLogger(RagService.class);
    private static final String LIST_CUSTOMERS_URI = "/customers";
    private static final String GET_CUSTOMERS_URI = "/customers/{customerId}";
    private static final String PUT_CUSTOMERS_URI = "/customers/{customerId}";
    private static final String LIST_RAG_URLS_URI = "/customers/{customerId}/rag-urls";
    private static final String PUT_RAG_URLS_URI = "/customers/{customerId}/rag-urls/{id}";
    private static final CountDownLatch restartLatch = new CountDownLatch(1);
    private static final long RESTART_DELAY_MILLISECONDS = 5000;

    private static final ConcurrentHashMap<Long, RagAgent> ragAgents = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, List<RagUrl>> ragUrls = new ConcurrentHashMap<>();

    private final boolean debug;
    private final RestClient apiRestClient;
    private final ExceptionManager exceptionManager;
    private final CrawlingService crawlingService;
    private final DataSource dataSource;
    private ExecutorService ragServiceExecutor;

    public RagService(
            @Value("${langchain.debug}") boolean debug,
            @Qualifier("apiRestClient") RestClient apiRestClient,
            ExceptionManager exceptionManager,
            CrawlingService crawlingService,
            DataSource dataSource) {
        this.debug = debug;
        this.apiRestClient = apiRestClient;
        this.exceptionManager = exceptionManager;
        this.crawlingService = crawlingService;
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    void startRagService() {
        LOG.info("Starting mailbox service");

        List<Customer> customers = null;

        try {
            // Blocking request
            customers =
                    apiRestClient
                            .get()
                            .uri(LIST_CUSTOMERS_URI)
                            .retrieve()
                            .body(new ParameterizedTypeReference<List<Customer>>() {});
        } catch (Exception e) {
            exceptionManager.handleException(
                    new ServiceException("Failed to fetch customers", e, true));
        }

        if (customers == null || customers.isEmpty()) {
            if (restartLatch.getCount() == 1) {
                restartLatch.countDown();
                LOG.error(
                        "No customers found. Restarting once in {} seconds.",
                        (float) RESTART_DELAY_MILLISECONDS / 1000);
                try {
                    Thread.sleep(RESTART_DELAY_MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                startRagService();
            } else {
                ServiceException serviceException =
                        new ServiceException("No customers found. Giving up.", true);
                exceptionManager.handleException(serviceException);
            }
            return;
        }

        LOG.info("Received {} customers from API", customers.size());

        this.ragServiceExecutor = Executors.newFixedThreadPool(customers.size());

        try {
            submitDailyCrawlTasks(customers);
        } catch (Exception e) {
            exceptionManager.handleException(e);
        }
    }

    // spotless:off
    private void submitDailyCrawlTasks(List<Customer> customers) {
        for (Customer customer : customers) {
            LOG.info("Submitting daily crawl task for customer {}", customer.getId());

            if (customer.getNextCrawlAt() == null
                    || ((customer.getNextCrawlAt().toLocalDate().isEqual(LocalDate.now(BERLIN_ZONE)))
                    && !customer.getLastCrawlAt().toLocalDate().isEqual(LocalDate.now(BERLIN_ZONE)))) {
                List<RagUrl> ragUrls = getOrFetchRagUrls(customer.getId());
                RagAgent ragAgent = getOrCreateRagAgent(customer);
                ragAgent.clear();
                //ragServiceExecutor.submit(() -> performCrawlForCustomer(customer, ragUrls, ragAgent));
                performCrawlForCustomer(customer, ragUrls, ragAgent);
            } else {
                LOG.info("Skipping customer {} as it's next crawl is in the future", customer.getId());
            }
        }
    }
    // spotless:on

    public void triggerOnDemandCrawl(long customerId, Customer customer) {
        if (!customer.getId().equals(customerId)) {
            throw new IdConflictException();
        }

        List<RagUrl> ragUrls = getOrFetchRagUrls(customerId);
        RagAgent ragAgent = getOrCreateRagAgent(customer);
        ragAgent.clear();
        ragServiceExecutor.submit(() -> performCrawlForCustomer(customer, ragUrls, ragAgent));
    }

    public List<EmbeddingMatch<TextSegment>> search(long userId, long customerId, String text) {
        LOG.info("Searching for relevant embeddings for user {} (customer {})", userId, customerId);
        LOG.debug("Query text: {}", text);
        RagAgent ragAgent = ragAgents.get(customerId);
        return ragAgent.search(text);
    }

    private void performCrawlForCustomer(
            Customer customer, List<RagUrl> ragUrls, RagAgent ragAgent) {
        LOG.debug("Starting crawl for customer {}", customer.getId());

        List<CrawlingResult> crawlingResults = new ArrayList<>();

        for (RagUrl ragUrl : ragUrls) {
            CrawlingResult crawlingResult = crawlingService.crawl(ragUrl);
            if (crawlingResult != null) {
                crawlingResults.add(crawlingResult);
                ragUrl.setLastCrawlSuccessful(true);
            } else {
                ragUrl.setLastCrawlSuccessful(false);
            }
            apiRestClient.post().uri(PUT_RAG_URLS_URI).body(ragUrl).retrieve().toBodilessEntity();
        }

        ragAgent.embedAll(crawlingResults);

        customer.setLastCrawlAt(ZonedDateTime.now(BERLIN_ZONE));
        customer.setNextCrawlAt(
                customer.getLastCrawlAt().plusDays(customer.getCrawlFrequencyInDays()));

        apiRestClient
                .put()
                .uri(PUT_CUSTOMERS_URI, customer.getId())
                .body(customer)
                .retrieve()
                .toBodilessEntity();
    }

    public void onRagUrlCreated(long customerId, long id, RagUrl ragUrl) {
        if (!ragUrl.getCustomerId().equals(customerId) || !ragUrl.getId().equals(id)) {
            throw new IdConflictException();
        }

        if (!ragUrls.containsKey(customerId)) {
            Customer customer =
                    apiRestClient
                            .get()
                            .uri(GET_CUSTOMERS_URI, customerId)
                            .retrieve()
                            .body(Customer.class);

            if (customer == null) {
                throw new RuntimeException("Failed to retrieve customer");
            }

            ragUrls.put(customerId, List.of(ragUrl));
            ragAgents.put(customerId, new RagAgent(customer, dataSource, debug));
        } else {
            ragUrls.get(customerId).add(ragUrl);
            ragAgents.get(customerId).embed(crawlingService.crawl(ragUrl));
        }
    }

    public void onRagUrlDeleted(long customerId, long id, RagUrl ragUrl) {
        if (!ragUrl.getCustomerId().equals(customerId) || !ragUrl.getId().equals(id)) {
            throw new IdConflictException();
        }

        if (ragUrls.containsKey(customerId)) {
            ragUrls.get(customerId).remove(ragUrl);
            ragAgents.get(customerId).removeByRagUrl(ragUrl.getId());
        }
    }

    private List<RagUrl> getOrFetchRagUrls(long customerId) {
        return ragUrls.computeIfAbsent(customerId, id -> fetchRagUrlsByCustomer(customerId));
    }

    private List<RagUrl> fetchRagUrlsByCustomer(long customerId) {
        LOG.debug("Fetching rag urls by customer {}", customerId);

        // Blocking request
        return apiRestClient
                .get()
                .uri(LIST_RAG_URLS_URI, customerId)
                .retrieve()
                .body(new ParameterizedTypeReference<List<RagUrl>>() {});
    }

    private RagAgent getOrCreateRagAgent(Customer customer) {
        LOG.debug("Creating rag agent for customer {}", customer.getId());
        return ragAgents.computeIfAbsent(
                customer.getId(), id -> new RagAgent(customer, dataSource, debug));
    }
}
