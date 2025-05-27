package de.flowsuite.mailboxservice.message;

import static de.flowsuite.mailflow.common.util.Util.BERLIN_ZONE;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;

import de.flowsuite.mailboxservice.exception.FolderException;
import de.flowsuite.mailboxservice.exception.MailboxServiceExceptionManager;
import de.flowsuite.mailboxservice.exception.ProcessingException;
import de.flowsuite.mailflow.common.client.ApiClient;
import de.flowsuite.mailflow.common.client.LlmServiceClient;
import de.flowsuite.mailflow.common.dto.CategorisationRequest;
import de.flowsuite.mailflow.common.dto.CategorisationResponse;
import de.flowsuite.mailflow.common.dto.CreateMessageLogEntryRequest;
import de.flowsuite.mailflow.common.dto.GenerationRequest;
import de.flowsuite.mailflow.common.entity.BlacklistEntry;
import de.flowsuite.mailflow.common.entity.MessageCategory;
import de.flowsuite.mailflow.common.entity.User;
import de.flowsuite.mailflow.common.exception.IdConflictException;

import jakarta.mail.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MessageService {

    private static final Logger LOG = LoggerFactory.getLogger(MessageService.class);

    private static final String DEFAULT_CATEGORY = "Default";
    private static final String NO_REPLY_CATEGORY = "No Reply";

    public static final ConcurrentHashMap<Long, List<MessageCategory>> messageCategories =
            new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<Long, List<BlacklistEntry>> blacklist =
            new ConcurrentHashMap<>();

    private final ApiClient apiClient;
    private final LlmServiceClient llmServiceClient;
    private final MessageReplyHandler replyHandler;
    private final MailboxServiceExceptionManager mailboxServiceExceptionManager;

    MessageService(
            ApiClient apiClient,
            LlmServiceClient llmServiceRestClient,
            MessageReplyHandler replyHandler,
            MailboxServiceExceptionManager mailboxServiceExceptionManager) {
        this.apiClient = apiClient;
        this.llmServiceClient = llmServiceRestClient;
        this.replyHandler = replyHandler;
        this.mailboxServiceExceptionManager = mailboxServiceExceptionManager;
    }

    // spotless:off
    public CompletableFuture<Void> processMessageAsync(
            Message message, Store store, Transport transport, IMAPFolder inbox, User user) {
        try {
            LOG.info("Processing message for user {}", user.getId());

            if (!(message instanceof IMAPMessage originalMessage)) {
                LOG.error("Aborting: message if not an IMAPMessage");
                return CompletableFuture.completedFuture(null);
            }

            originalMessage.setPeek(true);

            List<BlacklistEntry> blacklistEntries = getOrFetchBlacklist(user);
            String fromEmailAddress = MessageUtil.extractFromEmailAddress(originalMessage);
            if (blacklistEntries.stream()
                    .anyMatch(entry -> entry.getBlacklistedEmailAddress().equalsIgnoreCase(fromEmailAddress))) {
                LOG.info("Aborting: from email address is blacklisted");
                return CompletableFuture.completedFuture(null);
            }

            List<MessageCategory> categories = getOrFetchMessageCategories(user);
            String text = MessageUtil.getCleanedText(originalMessage);

            if (categories.size() == 1) {
                MessageCategory messageCategory = categories.get(0);
                CategorisationResponse categorisationResponse = new CategorisationResponse(messageCategory, null, null, null, null);
                return handleMessageCategoryAsync(originalMessage, categorisationResponse, store, transport, inbox, user);
            } else {
                // We use thenCompose to chain the next action based on the result of categorising the message.
                // This allows us to perform further asynchronous operations.
                return categoriseMessageAsync(user, text, categories)
                        .thenCompose(categorisationResponse -> {
                            try {
                                return handleMessageCategoryAsync(originalMessage, categorisationResponse, store, transport, inbox, user);
                            } catch (ProcessingException | MessagingException | IOException e) {
                                return CompletableFuture.failedFuture(e);
                            }
                        })
                        .exceptionally(e -> {
                            ProcessingException processingException =
                                    new ProcessingException(
                                            String.format("Failed to process message for user %d", user.getId()),
                                            e,
                                            true);
                                    mailboxServiceExceptionManager.handleException(processingException);
                                    return null;
                                });
            }
        } catch (ProcessingException | MessagingException | IOException e) {
            ProcessingException processingException =
                    new ProcessingException(
                            String.format("Failed to process message for user %d", user.getId()),
                            e,
                            true);
            mailboxServiceExceptionManager.handleException(processingException);
            return CompletableFuture.completedFuture(null);
        }
    }
    // spotless:on

    private CompletableFuture<Void> handleMessageCategoryAsync(
            IMAPMessage originalMessage,
            CategorisationResponse categorisationResponse,
            Store store,
            Transport transport,
            IMAPFolder inbox,
            User user)
            throws ProcessingException, MessagingException, IOException {

        if (categorisationResponse == null || categorisationResponse.messageCategory() == null) {
            handleFailedCategorisation(originalMessage, store, inbox, user);
            return CompletableFuture.completedFuture(null); // already done
        }

        MessageCategory category = categorisationResponse.messageCategory();

        if (category.getReply()) {
            CompletableFuture<Void> future =
                    generateReplyMessageAsync(
                            originalMessage, categorisationResponse, store, transport, inbox, user);

            if (!isDefaultOrNoReplyCategory(category)) {
                future =
                        future.thenCompose(
                                v -> {
                                    try {
                                        moveMessageToCategoryFolder(
                                                originalMessage, store, inbox, category);
                                        return CompletableFuture.completedFuture(null);
                                    } catch (MessagingException | ProcessingException e) {
                                        return CompletableFuture.failedFuture(e);
                                    }
                                });
            }

            return future;
        }

        if (!isDefaultOrNoReplyCategory(category)) {
            moveMessageToCategoryFolder(originalMessage, store, inbox, category);
        }

        String fromEmailAddress = MessageUtil.extractFromEmailAddress(originalMessage);
        ZonedDateTime now = ZonedDateTime.now(BERLIN_ZONE);
        ZonedDateTime receivedAt =
                ZonedDateTime.ofInstant(originalMessage.getReceivedDate().toInstant(), BERLIN_ZONE);
        int processingTimeInSeconds = (int) Duration.between(receivedAt, now).getSeconds();

        CreateMessageLogEntryRequest request =
                new CreateMessageLogEntryRequest(
                        user.getId(),
                        user.getCustomerId(),
                        false,
                        false,
                        category.getCategory(),
                        null, // TODO
                        fromEmailAddress,
                        originalMessage.getSubject(),
                        receivedAt,
                        now,
                        processingTimeInSeconds,
                        categorisationResponse.llmUsed(),
                        categorisationResponse.inputTokens(),
                        categorisationResponse.outputTokens(),
                        categorisationResponse.totalTokens(),
                        null,
                        null,
                        null,
                        null);

        apiClient.createMessageLogEntry(request);

        return CompletableFuture.completedFuture(null); // nothing to do
    }

    private void handleFailedCategorisation(
            IMAPMessage message, Store store, IMAPFolder inbox, User user)
            throws MessagingException, FolderException {
        LOG.warn("Failed to categorise message for user {}", user.getId());
        FolderUtil.moveToManualReviewFolder(user, message, store, inbox);
    }

    private boolean isDefaultOrNoReplyCategory(MessageCategory messageCategory) {
        String category = messageCategory.getCategory();
        return DEFAULT_CATEGORY.equalsIgnoreCase(category)
                || NO_REPLY_CATEGORY.equalsIgnoreCase(category);
    }

    private List<MessageCategory> getOrFetchMessageCategories(User user) {
        return messageCategories.computeIfAbsent(
                user.getCustomerId(),
                id -> apiClient.listMessageCategories(user.getCustomerId())); // Blocking request
    }

    private List<BlacklistEntry> getOrFetchBlacklist(User user) {
        return blacklist.computeIfAbsent(
                user.getId(),
                id ->
                        apiClient.listBlacklistEntries(
                                user.getCustomerId(), user.getId())); // Blocking request
    }

    private CompletableFuture<Void> generateReplyMessageAsync(
            IMAPMessage originalMessage,
            CategorisationResponse categorisationResponse,
            Store store,
            Transport transport,
            IMAPFolder inbox,
            User user)
            throws MessagingException, IOException, ProcessingException {
        LOG.debug("Generating reply for user {}...", user.getId());

        List<IMAPMessage> messageThread =
                MessageUtil.fetchMessageThread(originalMessage, store, inbox);
        String threadBody = MessageUtil.buildThreadBody(messageThread, user);

        LOG.debug("Message thread body: {}", threadBody);

        String fromEmailAddress = MessageUtil.extractFromEmailAddress(originalMessage);
        ZonedDateTime receivedAt =
                ZonedDateTime.ofInstant(originalMessage.getReceivedDate().toInstant(), BERLIN_ZONE);

        return generateReplyAsync(
                        user,
                        threadBody,
                        fromEmailAddress,
                        originalMessage.getSubject(),
                        receivedAt,
                        categorisationResponse)
                .thenAccept(
                        reply -> {
                            try {
                                replyHandler.handleReply(
                                        user, originalMessage, reply, store, transport, inbox);
                            } catch (MessagingException | ProcessingException e) {
                                mailboxServiceExceptionManager.handleException(e);
                            }
                        });
    }

    private void moveMessageToCategoryFolder(
            IMAPMessage originalMessage,
            Store store,
            IMAPFolder inbox,
            MessageCategory messageCategory)
            throws MessagingException, ProcessingException {
        LOG.debug("Moving message to messageCategory folder...");

        IMAPFolder targetFolder = FolderUtil.getFolderByName(store, messageCategory.getCategory());

        if (targetFolder == null) {
            targetFolder = FolderUtil.createFolderByName(store, messageCategory.getCategory());
        }

        FolderUtil.moveToFolder(originalMessage, inbox, targetFolder);
    }

    CompletableFuture<CategorisationResponse> categoriseMessageAsync(
            User user, String text, List<MessageCategory> categories) {
        return CompletableFuture.supplyAsync(
                () -> {
                    CategorisationRequest request =
                            CategorisationRequest.builder()
                                    .user(user)
                                    .text(text.trim())
                                    .categories(categories)
                                    .build();

                    return llmServiceClient.categorise(request);
                });
    }

    CompletableFuture<String> generateReplyAsync(
            User user,
            String text,
            String fromEmailAddress,
            String subject,
            ZonedDateTime receivedAt,
            CategorisationResponse categorisationResponse) {
        return CompletableFuture.supplyAsync(
                () -> {
                    GenerationRequest request =
                            GenerationRequest.builder()
                                    .user(user)
                                    .text(text.trim())
                                    .fromEmailAddress(fromEmailAddress)
                                    .subject(subject)
                                    .receivedAt(receivedAt)
                                    .categorisationResponse(categorisationResponse)
                                    .build();

                    return llmServiceClient.generateReply(request);
                });
    }

    void onMessageCategoriesUpdated(long customerId, List<MessageCategory> categories) {
        LOG.debug("Updating message categories for customer {}", customerId);
        for (MessageCategory category : categories) {
            if (!category.getCustomerId().equals(customerId)) {
                throw new IdConflictException();
            }
        }
        messageCategories.put(customerId, categories);
    }

    void onBlacklistUpdated(long userId, List<BlacklistEntry> blacklistEntries) {
        LOG.debug("Updating blacklist for user {}", userId);
        for (BlacklistEntry blacklistEntry : blacklistEntries) {
            if (!blacklistEntry.getUserId().equals(userId)) {
                throw new IdConflictException();
            }
        }
        blacklist.put(userId, blacklistEntries);
    }
}
