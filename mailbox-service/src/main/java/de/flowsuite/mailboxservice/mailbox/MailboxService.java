package de.flowsuite.mailboxservice.mailbox;

import de.flowsuite.mailboxservice.exception.MailboxException;
import de.flowsuite.mailboxservice.exception.MailboxServiceExceptionManager;
import de.flowsuite.mailflow.common.client.ApiClient;
import de.flowsuite.mailflow.common.entity.Customer;
import de.flowsuite.mailflow.common.entity.User;
import de.flowsuite.mailflow.common.exception.IdConflictException;
import de.flowsuite.mailflow.common.util.AesUtil;
import de.flowsuite.mailflow.common.util.Util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

@Service
public class MailboxService {

    // spotless:off
    private static final Logger LOG = LoggerFactory.getLogger(MailboxService.class);

    private static final ConcurrentHashMap<Long, Boolean> testVersionByCustomer = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, MailboxListenerTask> testVersionTasksByCustomer = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<Long, MailboxListenerTask> tasksByUser = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<Long, Future<Void>> futuresByUser = new ConcurrentHashMap<>();

    static final long TIMEOUT_MS = 3000;

    private final ApiClient apiClient;
    private final MailboxConnectionManager mailboxConnectionManager;
    private final ExecutorService mailboxExecutor;
    private final MailboxServiceExceptionManager exceptionManager;
    private final Environment environment;
    // spotless:on

    MailboxService(
            ApiClient apiClient,
            MailboxConnectionManager mailboxConnectionManager,
            @Lazy MailboxServiceExceptionManager exceptionManager,
            Environment environment) {
        this.apiClient = apiClient;
        this.mailboxConnectionManager = mailboxConnectionManager;
        this.mailboxExecutor = Executors.newCachedThreadPool();
        this.exceptionManager = exceptionManager;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    void startMailboxService() {
        if (Arrays.asList(environment.getActiveProfiles()).contains("test")) {
            return;
        }

        LOG.info("Starting mailbox service");

        List<User> users = null;

        // Blocking request
        users = apiClient.listUsers();

        LOG.info("Received {} users from API", users.size());

        for (User user : users) {
            try {
                startMailboxListenerForUser(user);
            } catch (Exception e) {
                exceptionManager.handleException(e);
            }
        }
    }

    public void startMailboxListenerForUser(User user) throws MailboxException {
        startMailboxListenerForUser(user, false);
    }

    void startMailboxListenerForUser(User user, boolean shouldDelayStart) throws MailboxException {
        LOG.info("Starting mailbox listener for user {}", user.getId());

        if (user.getSettings() == null) {
            throw new MailboxException("User settings are null", false);
        }

        Util.validateMailboxSettings(
                user.getSettings().getImapHost(),
                user.getSettings().getSmtpHost(),
                user.getSettings().getImapPort(),
                user.getSettings().getSmtpPort());

        if (isAlreadyRunning(user)) return;
        if (isExecutionDisabled(user)) return;

        if (!testVersionByCustomer.containsKey(user.getCustomerId())) {
            // Blocking request
            boolean testVersion = apiClient.isCustomerTestVersion(user.getCustomerId());
            testVersionByCustomer.put(user.getCustomerId(), testVersion);
        }

        Customer customer = null;
        if (testVersionByCustomer.get(user.getCustomerId())) {
            LOG.info(
                    "User {} (customer {}) is configured to use the test version",
                    user.getId(),
                    user.getCustomerId());

            if (!testVersionTasksByCustomer.containsKey(user.getCustomerId())) {
                LOG.debug(
                        "Fetching customer {} details to configure test version for user {}",
                        user.getCustomerId(),
                        user.getId());

                // Blocking request
                customer = apiClient.getCustomer(user.getCustomerId());

                if (customer != null && customer.isTestVersion()) {
                    user.setEmailAddress(AesUtil.encrypt(customer.getIonosUsername()));
                    user.getSettings()
                            .setMailboxPassword(AesUtil.encrypt(customer.getIonosPassword()));
                }
            } else {
                LOG.info(
                        "Test version task for customer {} is already running. Skipping user {}",
                        user.getCustomerId(),
                        user.getId());
            }
        }

        MailboxListenerTask task =
                new MailboxListenerTask(
                        user, mailboxConnectionManager, exceptionManager, shouldDelayStart);

        if (customer != null
                && customer.isTestVersion()
                && !testVersionTasksByCustomer.containsKey(user.getCustomerId())) {
            testVersionTasksByCustomer.put(user.getCustomerId(), task);
        }

        Future<Void> future = mailboxExecutor.submit(task);

        awaitImapIdleOrFail(user, task, future);

        tasksByUser.put(user.getId(), task);
        futuresByUser.put(user.getId(), future);

        LOG.info("Mailbox listener for user {} started successfully", user.getId());
    }

    private boolean isAlreadyRunning(User user) {
        if (tasksByUser.containsKey(user.getId()) || futuresByUser.containsKey(user.getId())) {
            LOG.info("Aborting: mailbox listener for user {} is already running", user.getId());
            return true;
        }
        return false;
    }

    private boolean isExecutionDisabled(User user) {
        if (!user.getSettings().isExecutionEnabled()) {
            LOG.info("Aborting: execution is disabled for user {}", user.getId());
            return true;
        }
        return false;
    }

    private void awaitImapIdleOrFail(User user, MailboxListenerTask task, Future<Void> future)
            throws MailboxException {
        if (!task.hasEnteredImapIdleMode()) {
            future.cancel(true);
            throw new MailboxException(
                    String.format(
                            "Failed to start mailbox listener for user %d: The inbox did not enter"
                                    + " IDLE mode within the expected timeout period.",
                            user.getId()),
                    true);
            // TODO automatic retry?
        }
    }

    void onUserCreated(long userId, User createdUser) throws MailboxException {
        LOG.info("New user received {}", createdUser.getId());

        if (!createdUser.getId().equals(userId)) {
            throw new IdConflictException();
        }

        startMailboxListenerForUser(createdUser);
    }

    void onUserUpdated(long userId, User updatedUser) throws MailboxException {
        LOG.info("Restarting mailbox listener for user {} due to update", updatedUser.getId());

        if (!updatedUser.getId().equals(userId)) {
            throw new IdConflictException();
        }

        MailboxListenerTask task = tasksByUser.get(updatedUser.getId());
        Future<Void> future = futuresByUser.get(updatedUser.getId());

        if (task != null && future != null) {
            terminateMailboxListenerForUser(task, future, updatedUser.getId());
        }

        startMailboxListenerForUser(updatedUser, true);
    }

    void onCustomerTestVersionUpdated(long customerId, boolean testVersion)
            throws MailboxException {
        LOG.info("Test version updated for customer {}: {}", customerId, testVersion);

        if (!testVersion) {
            testVersionByCustomer.put(customerId, false);

            MailboxListenerTask task = testVersionTasksByCustomer.remove(customerId);

            terminateMailboxListenerForUser(
                    task, futuresByUser.get(task.getUser().getId()), task.getUser().getId());

            // Blocking request
            List<User> users = apiClient.listUsersByCustomer(customerId);

            if (users == null) {
                throw new MailboxException(
                        String.format("Failed to fetch users for customer %d", customerId), false);
            }

            for (User user : users) {
                try {
                    startMailboxListenerForUser(user);
                } catch (Exception e) {
                    exceptionManager.handleException(e);
                }
            }
        }
    }

    public void terminateMailboxListenerForUser(
            MailboxListenerTask task, Future<Void> future, long userId) throws MailboxException {
        LOG.info("Terminating mailbox listener for user {}", userId);

        try {
            task.disconnect();
        } catch (MailboxException e) {
            throw new MailboxException(
                    String.format("Failed to terminate mailbox listener of user %d", userId),
                    e,
                    e.shouldNotifyAdmin());
        }

        // Cancel the task and interrupt the thread
        future.cancel(true);

        try {
            future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS); // Wait for task to fully finish
        } catch (CancellationException | TimeoutException e) {
            throw new MailboxException(
                    String.format(
                            "Mailbox listener for user %d did not terminate cleanly in time",
                            userId),
                    e,
                    false);
        } catch (Exception e) {
            throw new MailboxException(
                    String.format(
                            "Error while waiting for mailbox listener to terminate for user %d",
                            userId),
                    e,
                    false);
        }

        tasksByUser.remove(userId);
        futuresByUser.remove(userId);

        LOG.info("Mailbox listener for user {} fully terminated successfully", userId);
    }
}
