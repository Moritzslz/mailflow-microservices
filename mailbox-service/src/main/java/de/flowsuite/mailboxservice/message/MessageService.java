package de.flowsuite.mailboxservice.message;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;

import de.flowsuite.mailboxservice.exception.MailboxServiceExceptionManager;
import de.flowsuite.mailboxservice.exception.ProcessingException;
import de.flowsuite.mailflow.common.dto.LlmServiceRequest;
import de.flowsuite.mailflow.common.entity.BlacklistEntry;
import de.flowsuite.mailflow.common.entity.MessageCategory;
import de.flowsuite.mailflow.common.entity.User;
import de.flowsuite.mailflow.common.exception.IdConflictException;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.Transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MessageService {

    private static final Logger LOG = LoggerFactory.getLogger(MessageService.class);
    private static final String LIST_MESSAGE_CATEGORIES_URI =
            "/customers/{customerId}/message-categories";
    private static final String LIST_BLACKLIST_URI =
            "/customers/{customerId}/users/{userId}/blacklist";

    public static final ConcurrentHashMap<Long, List<MessageCategory>> messageCategories =
            new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<Long, List<BlacklistEntry>> blacklist =
            new ConcurrentHashMap<>();

    private final RestClient apiRestClient;
    private final RestClient llmServiceRestClient;
    private final MessageReplyHandler replyHandler;
    private final MailboxServiceExceptionManager mailboxServiceExceptionManager;

    MessageService(
            @Qualifier("apiRestClient") RestClient apiRestClient,
            @Qualifier("llmServiceRestClient") RestClient llmServiceRestClient,
            MessageReplyHandler replyHandler,
            MailboxServiceExceptionManager mailboxServiceExceptionManager) {
        this.apiRestClient = apiRestClient;
        this.llmServiceRestClient = llmServiceRestClient;
        this.replyHandler = replyHandler;
        this.mailboxServiceExceptionManager = mailboxServiceExceptionManager;
    }

    // spotless:off
    public void processMessageAsync(
            Message message, Store store, Transport transport, IMAPFolder inbox, User user) {
        try {
            LOG.info("Processing message for user {}", user.getId());

            if (!(message instanceof IMAPMessage originalMessage)) {
                LOG.error("Aborting: message if not an IMAPMessage");
                return;
            }

            originalMessage.setPeek(true);

            List<BlacklistEntry> blacklistEntries = getOrFetchBlacklist(user);
            String from = MessageUtil.extractFromEmail(originalMessage);
            if (blacklistEntries.stream()
                    .anyMatch(entry -> entry.getBlacklistedEmailAddress().equalsIgnoreCase(from))) {
                LOG.info("Aborting: from email address is blacklisted");
                return;
            }

            List<MessageCategory> categories = getOrFetchMessageCategories(user);
            String text = MessageUtil.getCleanedText(originalMessage);

            if (categories.size() == 1) {
                MessageCategory messageCategory = categories.get(0);
                handleMessageCategoryAsync(originalMessage, messageCategory, store, transport, inbox, user);
            } else {
                // We use thenCompose to chain the next action based on the result of categorising the message.
                // This allows us to perform further asynchronous operations.
                categoriseMessageAsync(user, text, categories)
                        .thenCompose(messageCategory -> {
                            try {
                                return handleMessageCategoryAsync(originalMessage, messageCategory, store, transport, inbox, user);
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
        }
    }
    // spotless:on

    private CompletableFuture<Void> handleMessageCategoryAsync(
            IMAPMessage originalMessage,
            MessageCategory messageCategory,
            Store store,
            Transport transport,
            IMAPFolder inbox,
            User user)
            throws ProcessingException, MessagingException, IOException {
        if (messageCategory == null) {
            LOG.warn("Failed to categorise message for user {}", user.getId());
            FolderUtil.moveToManualReviewFolder(user, originalMessage, store, inbox);
            return CompletableFuture.completedFuture(null); // already done
        } else if (messageCategory.getReply()) {
            return generateReplyMessageAsync(
                    originalMessage, messageCategory, store, transport, inbox, user);
        } else if (!messageCategory.getCategory().equalsIgnoreCase("default")) {
            moveMessageToCategoryFolder(originalMessage, store, inbox, messageCategory);
            return CompletableFuture.completedFuture(null); // already done
        } else {
            return CompletableFuture.completedFuture(null); // nothing to do
        }
    }

    private List<MessageCategory> getOrFetchMessageCategories(User user) {
        return messageCategories.computeIfAbsent(
                user.getCustomerId(), id -> fetchMessageCategoriesByUser(user));
    }

    private List<BlacklistEntry> getOrFetchBlacklist(User user) {
        return blacklist.computeIfAbsent(user.getId(), id -> fetchBlacklistByUser(user));
    }

    private CompletableFuture<Void> generateReplyMessageAsync(
            IMAPMessage originalMessage,
            MessageCategory messageCategory,
            Store store,
            Transport transport,
            IMAPFolder inbox,
            User user)
            throws MessagingException, IOException, ProcessingException {
        LOG.debug("Generating reply for user {}...", user.getId());

        List<IMAPMessage> messageThread =
                MessageUtil.fetchMessageThread(originalMessage, store, inbox);
        String threadBody = MessageUtil.buildThreadBody(messageThread, user);

        return generateReplyAsync(user, threadBody, messageCategory)
                .thenAccept(
                        reply -> {
                            try {
                                replyHandler.handleReply(user, originalMessage, reply, store, transport, inbox);
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
        LOG.debug("Moving message to category folder...");

        IMAPFolder targetFolder = FolderUtil.getFolderByName(store, messageCategory.getCategory());

        if (targetFolder == null) {
            targetFolder = FolderUtil.createFolderByName(store, messageCategory.getCategory());
        }

        FolderUtil.moveToFolder(originalMessage, inbox, targetFolder);
    }

    CompletableFuture<MessageCategory> categoriseMessageAsync(
            User user, String text, List<MessageCategory> categories) {
        return CompletableFuture.supplyAsync(
                () -> {
                    LOG.debug(
                            "Calling llm service to categorise message for user {}...",
                            user.getId());

                    LlmServiceRequest request =
                            LlmServiceRequest.builder()
                                    .user(user)
                                    .text(text)
                                    .categories(categories)
                                    .build();

                    return llmServiceRestClient
                            .post()
                            .uri("")
                            .body(request)
                            .retrieve()
                            .body(MessageCategory.class);
                });
    }

    CompletableFuture<String> generateReplyAsync(
            User user, String text, MessageCategory messageCategory) {
        return CompletableFuture.supplyAsync(
                () -> {
                    LOG.debug("Calling llm service to generate reply for user {}...", user.getId());

                    LlmServiceRequest request =
                            LlmServiceRequest.builder()
                                    .user(user)
                                    .text(text)
                                    .categories(List.of(messageCategory))
                                    .build();

                    return llmServiceRestClient
                            .post()
                            .uri("")
                            .body(request)
                            .retrieve()
                            .body(String.class);
                });
    }

    List<MessageCategory> fetchMessageCategoriesByUser(User user) {
        LOG.debug("Fetching message categories for user {}", user.getId());

        // Blocking request
        return apiRestClient
                .get()
                .uri(LIST_MESSAGE_CATEGORIES_URI, user.getCustomerId())
                .retrieve()
                .body(new ParameterizedTypeReference<List<MessageCategory>>() {});
    }

    List<BlacklistEntry> fetchBlacklistByUser(User user) {
        LOG.debug("Fetching blacklist for user {}", user.getId());

        // Blocking request
        return apiRestClient
                .get()
                .uri(LIST_BLACKLIST_URI, user.getCustomerId(), user.getId())
                .retrieve()
                .body(new ParameterizedTypeReference<List<BlacklistEntry>>() {});
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
