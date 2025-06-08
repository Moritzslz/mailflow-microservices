package de.flowsuite.mailboxservice.message;

import static de.flowsuite.mailflow.common.util.Util.BERLIN_ZONE;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;

import de.flowsuite.mailboxservice.exception.FolderException;
import de.flowsuite.mailboxservice.exception.MailboxServiceExceptionManager;
import de.flowsuite.mailboxservice.exception.ProcessingException;
import de.flowsuite.mailflow.common.client.ApiClient;
import de.flowsuite.mailflow.common.client.LlmServiceClient;
import de.flowsuite.mailflow.common.dto.*;
import de.flowsuite.mailflow.common.entity.BlacklistEntry;
import de.flowsuite.mailflow.common.entity.MessageCategory;
import de.flowsuite.mailflow.common.entity.User;
import de.flowsuite.mailflow.common.exception.IdConflictException;

import jakarta.mail.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MessageService {

    private static final Logger LOG = LoggerFactory.getLogger(MessageService.class);

    private static final String DEFAULT_CATEGORY = "Default";
    private static final String NO_REPLY_CATEGORY = "No Reply";

    public static final ConcurrentHashMap<Long, List<MessageCategory>> messageCategoriesByUser =
            new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<Long, List<BlacklistEntry>> blacklistByUser =
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

        if (categorisationResponse == null || categorisationResponse.getMessageCategory() == null) {
            handleFailedCategorisation(originalMessage, store, inbox, user);
            return CompletableFuture.completedFuture(null); // already done
        }

        MessageCategory messageCategory = categorisationResponse.getMessageCategory();

        if (messageCategory.getReply()) {
            CompletableFuture<Boolean> future =
                    generateReplyMessageAsync(
                            originalMessage, categorisationResponse, store, transport, inbox, user);

            // Move message AFTER reply generation
            return future.thenCompose(
                    messageHasBeenMoved -> {
                        if (!isDefaultOrNoReplyCategory(messageCategory)) {
                            try {
                                if (!messageHasBeenMoved) {
                                    moveMessageToCategoryFolder(
                                            originalMessage, store, inbox, messageCategory);
                                }
                            } catch (MessagingException | ProcessingException e) {
                                return CompletableFuture.failedFuture(e);
                            }
                        }
                        return CompletableFuture.completedFuture(null);
                    });
        }

        if (!isDefaultOrNoReplyCategory(messageCategory)) {
            moveMessageToCategoryFolder(originalMessage, store, inbox, messageCategory);
        }

        String fromEmailAddress = MessageUtil.extractFromEmailAddress(originalMessage);
        ZonedDateTime receivedAt =
                ZonedDateTime.ofInstant(originalMessage.getReceivedDate().toInstant(), BERLIN_ZONE);

        apiClient.createMessageLogEntry(
                user.getCustomerId(),
                user.getId(),
                fromEmailAddress,
                originalMessage.getSubject(),
                receivedAt,
                categorisationResponse,
                null,
                messageCategory);

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
        return messageCategoriesByUser.computeIfAbsent(
                user.getCustomerId(),
                id -> apiClient.listMessageCategories(user.getCustomerId())); // Blocking request
    }

    private List<BlacklistEntry> getOrFetchBlacklist(User user) {
        return blacklistByUser.computeIfAbsent(
                user.getId(),
                id ->
                        apiClient.listBlacklistEntries(
                                user.getCustomerId(), user.getId())); // Blocking request
    }

    private CompletableFuture<Boolean> generateReplyMessageAsync(
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
        List<ThreadMessage> threadBody = MessageUtil.buildThreadBody(messageThread, user);

        LOG.debug("Message thread body contains {} messages", threadBody.size());

        for (ThreadMessage threadMessage : threadBody) {
            LOG.debug(threadMessage.toString());
        }

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
                .thenApply(
                        reply -> {
                            try {
                                return replyHandler.handleReply(
                                        user, originalMessage, reply, store, transport, inbox);
                            } catch (MessagingException | ProcessingException e) {
                                mailboxServiceExceptionManager.handleException(e);
                                return false;
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
            List<ThreadMessage> messageThread,
            String fromEmailAddress,
            String subject,
            ZonedDateTime receivedAt,
            CategorisationResponse categorisationResponse) {
        return CompletableFuture.supplyAsync(
                () -> {
                    GenerationRequest request =
                            GenerationRequest.builder()
                                    .user(user)
                                    .messageThread(messageThread)
                                    .fromEmailAddress(fromEmailAddress)
                                    .subject(subject.trim())
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
        messageCategoriesByUser.put(customerId, categories);
    }

    void onBlacklistUpdated(long userId, List<BlacklistEntry> blacklistEntries) {
        LOG.debug("Updating blacklist for user {}", userId);
        for (BlacklistEntry blacklistEntry : blacklistEntries) {
            if (!blacklistEntry.getUserId().equals(userId)) {
                throw new IdConflictException();
            }
        }
        blacklistByUser.put(userId, blacklistEntries);
    }
}
