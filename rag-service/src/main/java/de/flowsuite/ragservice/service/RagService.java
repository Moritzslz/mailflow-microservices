package de.flowsuite.ragservice.service;

import static de.flowsuite.mailflow.common.util.Util.BERLIN_ZONE;

import de.flowsuite.mailflow.common.client.ApiClient;
import de.flowsuite.mailflow.common.entity.Customer;
import de.flowsuite.mailflow.common.entity.RagUrl;
import de.flowsuite.mailflow.common.exception.IdConflictException;
import de.flowsuite.ragservice.agent.RagAgent;
import de.flowsuite.ragservice.exception.CrawlingException;
import de.flowsuite.shared.exception.ExceptionManager;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

@Service
public class RagService {

    private static final Logger LOG = LoggerFactory.getLogger(RagService.class);

    private static final ConcurrentHashMap<Long, RagAgent> ragAgents = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, List<RagUrl>> ragUrls = new ConcurrentHashMap<>();

    private final boolean debug;
    private final ApiClient apiClient;
    private final ExceptionManager exceptionManager;
    private final CrawlingService crawlingService;
    private final DataSource dataSource;
    private ExecutorService ragServiceExecutor;

    public RagService(
            @Value("${langchain.debug}") boolean debug,
            ApiClient apiClient,
            ExceptionManager exceptionManager,
            CrawlingService crawlingService,
            DataSource dataSource) {
        this.debug = debug;
        this.apiClient = apiClient;
        this.exceptionManager = exceptionManager;
        this.crawlingService = crawlingService;
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    void startRagService() {
        LOG.info("Starting mailbox service");

        // Blocking request
        List<Customer> customers = apiClient.listCustomers();

        LOG.info("Received {} customers from API", customers.size());

        this.ragServiceExecutor = Executors.newFixedThreadPool(customers.size());

        try {
            submitDailyCrawlTasks(customers);
        } catch (Exception e) {
            exceptionManager.handleException(e);
        }
    }

    // TODO important note: for this to work the service needs to be restarted daily
    private void submitDailyCrawlTasks(List<Customer> customers) {
        for (Customer customer : customers) {
            LOG.info("Submitting daily crawl task for customer {}", customer.getId());

            if (customer.getNextCrawlAt() == null
                    || ((customer.getNextCrawlAt()
                                    .toLocalDate()
                                    .isEqual(LocalDate.now(BERLIN_ZONE)))
                            && !customer.getLastCrawlAt()
                                    .toLocalDate()
                                    .isEqual(LocalDate.now(BERLIN_ZONE)))) {
                List<RagUrl> ragUrls = getOrFetchRagUrls(customer.getId());
                RagAgent ragAgent = getOrCreateRagAgent(customer);
                ragAgent.clear();
                ragServiceExecutor.submit(
                        () -> performCrawlForCustomer(customer, ragUrls, ragAgent));
            } else {
                LOG.info(
                        "Skipping customer {} as it's next crawl is in the future",
                        customer.getId());
            }
        }
    }

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
            CrawlingResult crawlingResult = null;
            try {
                crawlingResult = crawlingService.crawl(ragUrl);
            } catch (CrawlingException e) {
                exceptionManager.handleException(e);
            }
            if (crawlingResult != null) {
                crawlingResults.add(crawlingResult);
                ragUrl.setLastCrawlSuccessful(true);
            } else {
                ragUrl.setLastCrawlSuccessful(false);
            }
            // Blocking request
            apiClient.updateRagUrl(ragUrl);
        }

        ragAgent.embedAll(crawlingResults); // TODO notify admin if fails and set last crawl successful to false for all rag urls

        customer.setLastCrawlAt(ZonedDateTime.now(BERLIN_ZONE));
        customer.setNextCrawlAt(
                customer.getLastCrawlAt().plusDays(customer.getCrawlFrequencyInDays()));

        // Blocking request
        apiClient.updateCustomer(customer);
    }

    public void onRagUrlCreated(long customerId, long id, RagUrl ragUrl) {
        if (!ragUrl.getCustomerId().equals(customerId) || !ragUrl.getId().equals(id)) {
            throw new IdConflictException();
        }

        if (!ragUrls.containsKey(customerId)) {
            // Blocking request
            Customer customer = apiClient.getCustomer(customerId);

            if (customer == null) {
                throw new RuntimeException("Failed to retrieve customer");
            }

            ragUrls.put(customerId, List.of(ragUrl));
            ragAgents.put(customerId, new RagAgent(customer, dataSource, debug));
        } else {
            ragUrls.get(customerId).add(ragUrl);
            try {
                ragAgents.get(customerId).embed(crawlingService.crawl(ragUrl));
            } catch (CrawlingException e) {
                exceptionManager.handleException(e);
            }
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
        return ragUrls.computeIfAbsent(
                customerId, id -> apiClient.listRagUrls(customerId)); // Blocking request
    }

    private RagAgent getOrCreateRagAgent(Customer customer) {
        LOG.debug("Creating rag agent for customer {}", customer.getId());
        return ragAgents.computeIfAbsent(
                customer.getId(), id -> new RagAgent(customer, dataSource, debug));
    }
}
