package de.flowsuite.ragservice.service;

import static de.flowsuite.mailflow.common.util.Util.BERLIN_ZONE;

import de.flowsuite.mailflow.common.client.ApiClient;
import de.flowsuite.mailflow.common.dto.RagServiceResponse;
import de.flowsuite.mailflow.common.dto.ThreadMessage;
import de.flowsuite.mailflow.common.dto.UpdateCustomerCrawlStatusRequest;
import de.flowsuite.mailflow.common.entity.Customer;
import de.flowsuite.mailflow.common.entity.RagUrl;
import de.flowsuite.mailflow.common.exception.IdConflictException;
import de.flowsuite.mailflow.common.util.Util;
import de.flowsuite.ragservice.agent.RagAgent;
import de.flowsuite.ragservice.common.CrawlingResult;
import de.flowsuite.ragservice.exception.CrawlingException;
import de.flowsuite.shared.exception.ExceptionManager;
import de.flowsuite.shared.exception.ServiceException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

@Service
public class RagService {

    private static final Logger LOG = LoggerFactory.getLogger(RagService.class);

    private static final ConcurrentHashMap<Long, RagAgent> ragAgentsByCustomer =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, List<RagUrl>> ragUrlsByCustomer =
            new ConcurrentHashMap<>();

    private static final int MAX_INPUT_TOKENS = 8192;
    private static final double AVG_CHARS_PER_TOKEN = 3.5;

    private final boolean debug;
    private final ApiClient apiClient;
    private final ExceptionManager exceptionManager;
    private final CrawlingService crawlingService;
    private final DataSource dataSource;
    private ExecutorService ragServiceExecutor;
    private final Environment environment;

    public RagService(
            @Value("${langchain.debug}") boolean debug,
            ApiClient apiClient,
            ExceptionManager exceptionManager,
            CrawlingService crawlingService,
            DataSource dataSource,
            Environment environment) {
        this.debug = debug;
        this.apiClient = apiClient;
        this.exceptionManager = exceptionManager;
        this.crawlingService = crawlingService;
        this.dataSource = dataSource;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    void startRagService() {
        if (Arrays.asList(environment.getActiveProfiles()).contains("test")) {
            return;
        }

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

            RagAgent ragAgent = getOrCreateRagAgent(customer);

            if (customer.getNextCrawlAt() == null
                    || ((customer.getNextCrawlAt()
                                    .toLocalDate()
                                    .isEqual(LocalDate.now(BERLIN_ZONE)))
                            && !customer.getLastCrawlAt()
                                    .toLocalDate()
                                    .isEqual(LocalDate.now(BERLIN_ZONE)))) {
                List<RagUrl> ragUrls = getOrFetchRagUrls(customer.getId());
                ragAgent.removeAllEmbeddings();
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
        ragAgent.removeAllEmbeddings();
        ragServiceExecutor.submit(() -> performCrawlForCustomer(customer, ragUrls, ragAgent));
    }

    public Optional<RagServiceResponse> search(
            long userId, long customerId, List<ThreadMessage> messageThread) {
        LOG.info("Searching for relevant embeddings for user {} (customer {})", userId, customerId);

        String threadBody =
                Util.buildThreadBody(
                        messageThread, true, (int) (MAX_INPUT_TOKENS * AVG_CHARS_PER_TOKEN));
        LOG.debug("Thread body:\n{}", threadBody);

        RagAgent ragAgent = ragAgentsByCustomer.get(customerId);
        return ragAgent.search(threadBody);
    }

    private void performCrawlForCustomer(
            Customer customer, List<RagUrl> ragUrls, RagAgent ragAgent) {
        LOG.debug("Starting crawl for customer {}", customer.getId());

        List<CrawlingResult> crawlingResults = new ArrayList<>();

        for (RagUrl ragUrl : ragUrls) {
            CrawlingResult crawlingResult = null;
            boolean crawlSuccessful;
            try {
                crawlingResult = crawlingService.crawl(ragUrl);
            } catch (CrawlingException e) {
                exceptionManager.handleException(e);
            }
            if (crawlingResult != null) {
                crawlingResults.add(crawlingResult);
                crawlSuccessful = true;
            } else {
                crawlSuccessful = false;
            }
            CompletableFuture.runAsync(
                    () ->
                            apiClient.updateRagUrlCrawlStatus(
                                    customer.getId(), ragUrl.getId(), crawlSuccessful));
        }

        try {
            ragAgent.embedAll(crawlingResults);
        } catch (Exception e) {
            exceptionManager.handleException(
                    new ServiceException(
                            String.format(
                                    "Failed to embed rag urls for customer %d", customer.getId()),
                            e,
                            true));

            for (RagUrl ragUrl : ragUrls) {
                CompletableFuture.runAsync(
                        () ->
                                apiClient.updateRagUrlCrawlStatus(
                                        customer.getId(), ragUrl.getId(), false));
            }

            return;
        }

        ZonedDateTime now = ZonedDateTime.now(BERLIN_ZONE);
        UpdateCustomerCrawlStatusRequest request =
                new UpdateCustomerCrawlStatusRequest(
                        customer.getId(), now, now.plusDays(customer.getCrawlFrequencyInDays()));

        CompletableFuture.runAsync(() -> apiClient.updateCustomerCrawlStatus(request));
    }

    public void onRagUrlCreated(long customerId, long id, RagUrl ragUrl) {
        LOG.info("Received new rag url {} for customer {}", ragUrl.getId(), customerId);

        if (!ragUrl.getCustomerId().equals(customerId) || !ragUrl.getId().equals(id)) {
            throw new IdConflictException();
        }

        if (!ragUrlsByCustomer.containsKey(customerId)) {
            // Blocking request
            Customer customer = apiClient.getCustomer(customerId);

            if (customer == null) {
                throw new RuntimeException("Failed to retrieve customer");
            }

            ragUrlsByCustomer.put(customerId, List.of(ragUrl));
            getOrCreateRagAgent(customer);
        } else {
            ragUrlsByCustomer.get(customerId).add(ragUrl);
        }

        try {
            ragAgentsByCustomer.get(customerId).embed(crawlingService.crawl(ragUrl));
        } catch (CrawlingException e) {
            exceptionManager.handleException(e);
        }
    }

    public void onRagUrlUpdated(long customerId, long id, RagUrl updatedRagUrl) {
        LOG.info("Updating rag url {} for customer {}", updatedRagUrl.getId(), customerId);

        if (!updatedRagUrl.getCustomerId().equals(customerId)
                || !updatedRagUrl.getId().equals(id)) {
            throw new IdConflictException();
        }

        if (ragUrlsByCustomer.containsKey(customerId)) {
            List<RagUrl> ragUrls = ragUrlsByCustomer.get(customerId);

            for (RagUrl ragUrl : ragUrls) {
                if (ragUrl.getId().equals(updatedRagUrl.getId())) {
                    ragUrls.remove(ragUrl);
                    ragUrls.add(updatedRagUrl);
                    RagAgent ragAgent = ragAgentsByCustomer.get(customerId);
                    ragAgent.removeByRagUrl(ragUrl.getId());
                    try {
                        ragAgent.embed(crawlingService.crawl(updatedRagUrl));
                    } catch (CrawlingException e) {
                        exceptionManager.handleException(e);
                    }
                    break;
                }
            }
        }
    }

    public void onRagUrlDeleted(long customerId, long id, RagUrl ragUrl) {
        LOG.info("Deleting rag url {} for customer {}", ragUrl.getId(), customerId);

        if (!ragUrl.getCustomerId().equals(customerId) || !ragUrl.getId().equals(id)) {
            throw new IdConflictException();
        }

        if (ragUrlsByCustomer.containsKey(customerId)) {
            ragUrlsByCustomer.get(customerId).remove(ragUrl);
            ragAgentsByCustomer.get(customerId).removeByRagUrl(ragUrl.getId());
        }
    }

    private List<RagUrl> getOrFetchRagUrls(long customerId) {
        return ragUrlsByCustomer.computeIfAbsent(
                customerId, id -> apiClient.listRagUrls(customerId)); // Blocking request
    }

    private RagAgent getOrCreateRagAgent(Customer customer) {
        LOG.debug("Creating rag agent for customer {}", customer.getId());
        return ragAgentsByCustomer.computeIfAbsent(
                customer.getId(), id -> new RagAgent(customer, dataSource, debug));
    }
}
