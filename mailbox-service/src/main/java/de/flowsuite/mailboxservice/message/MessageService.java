package de.flowsuite.mailboxservice.message;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;

import de.flowsuite.mailboxservice.exception.ExceptionManager;
import de.flowsuite.mailboxservice.exception.ProcessingException;
import de.flowsuite.mailflow.common.dto.LlmServiceRequest;
import de.flowsuite.mailflow.common.entity.BlacklistEntry;
import de.flowsuite.mailflow.common.entity.MessageCategory;
import de.flowsuite.mailflow.common.entity.User;

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
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MessageService {

    private static final Logger LOG = LoggerFactory.getLogger(MessageService.class);
    private static final String LIST_MESSAGE_CATEGORIES_URI =
            "/customers/{customerId}/message-categories";
    private static final String LIST_BLACKLIST_URI =
            "/customers/{customerId}/users/{userId}/blacklist";

    private static final ConcurrentHashMap<Long, List<MessageCategory>> messageCategories =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, List<BlacklistEntry>> blacklist =
            new ConcurrentHashMap<>();

    private final RestClient apiRestClient;
    private final RestClient llmServiceRestClient;
    private final MessageResponseHandler responseHandler;
    private final ExceptionManager exceptionManager;

    MessageService(
            @Qualifier("apiRestClient") RestClient apiRestClient,
            @Qualifier("llmServiceRestClient") RestClient llmServiceRestClient,
            MessageResponseHandler responseHandler,
            ExceptionManager exceptionManager) {
        this.apiRestClient = apiRestClient;
        this.llmServiceRestClient = llmServiceRestClient;
        this.responseHandler = responseHandler;
        this.exceptionManager = exceptionManager;
    }

    public void processMessage(
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
            MessageCategory messageCategory = resolveMessageCategory(user, text, categories);

            if (messageCategory.getIsReply()) {
                handleReplyMessage(originalMessage, messageCategory, store, transport, inbox, user);
            } else if (!messageCategory.getCategory().equalsIgnoreCase("default")) {
                moveMessageToCategoryFolder(originalMessage, store, inbox, messageCategory);
            }
        } catch (ProcessingException | MessagingException | IOException e) {
            ProcessingException processingException =
                    new ProcessingException(
                            String.format("Failed to process message for user %d", user.getId()),
                            false);
            exceptionManager.handleException(processingException);
        }
    }

    private MessageCategory resolveMessageCategory(
            User user, String text, List<MessageCategory> categories) {
        return (categories.size() == 1)
                ? categories.get(0)
                : categoriseMessageAsync(user, text, categories);
    }

    private List<MessageCategory> getOrFetchMessageCategories(User user) {
        return messageCategories.computeIfAbsent(
                user.getId(), id -> fetchMessageCategoriesByUser(user));
    }

    private List<BlacklistEntry> getOrFetchBlacklist(User user) {
        return blacklist.computeIfAbsent(user.getId(), id -> fetchBlacklistByUser(user));
    }

    private void handleReplyMessage(
            IMAPMessage originalMessage,
            MessageCategory messageCategory,
            Store store,
            Transport transport,
            IMAPFolder inbox,
            User user)
            throws MessagingException, IOException, ProcessingException {
        LOG.debug("Generating reply...");

        List<IMAPMessage> messageThread =
                MessageUtil.fetchMessageThread(originalMessage, store, inbox);
        String threadBody = MessageUtil.buildThreadBody(messageThread, user);

        String response = generateResponseAsync(user, threadBody, messageCategory);

        responseHandler.handleResponse(user, originalMessage, response, store, transport, inbox);
    }

    private void moveMessageToCategoryFolder(
            IMAPMessage originalMessage,
            Store store,
            IMAPFolder inbox,
            MessageCategory messageCategory)
            throws MessagingException, IOException, ProcessingException {
        LOG.debug("Moving message to category folder...");

        IMAPFolder targetFolder = FolderUtil.getFolderByName(store, messageCategory.getCategory());

        if (targetFolder == null) {
            targetFolder = FolderUtil.createFolderByName(store, messageCategory.getCategory());
        }

        FolderUtil.moveToFolder(originalMessage, inbox, targetFolder);
    }

    MessageCategory categoriseMessageAsync(
            User user, String text, List<MessageCategory> categories) {
        LOG.debug("Calling llm service to categorise message...");
        // TODO Make async API call to LLM Service
        LlmServiceRequest request =
                LlmServiceRequest.builder().user(user).text(text).categories(categories).build();

        MessageCategory mockCategory1 =
                MessageCategory.builder().category("testCategory1").isReply(true).build();
        MessageCategory mockCategory2 =
                MessageCategory.builder().category("testCategory2").isReply(false).build();
        MessageCategory mockCategory3 =
                MessageCategory.builder().category("testCategory3").isReply(true).build();

        Random random = new Random();
        int randomNumber = random.nextInt(3);
        LOG.debug("Random number: {}", randomNumber);

        return switch (randomNumber) {
            case 0 -> mockCategory1;
            case 1 -> mockCategory2;
            case 2 -> mockCategory3;
            default -> null;
        };

        // return llmServiceRestClient.post().body(request).retrieve().body(MessageCategory.class);
    }

    String generateResponseAsync(User user, String text, MessageCategory messageCategory) {
        LOG.debug("Calling llm service to generate response...");
        // TODO Make async API call to LLM Service
        LlmServiceRequest request =
                LlmServiceRequest.builder()
                        .user(user)
                        .text(text)
                        .categories(List.of(messageCategory))
                        .build();

        Random random = new Random();
        int randomNumber = random.nextInt(2);
        return switch (randomNumber) {
            case 0 -> "Test Response 1 (draft)";
            case 1 -> "Test Response 2 (auto reply)";
            default -> null;
        };

        // return llmServiceRestClient.post().body(request).retrieve().body(String.class);
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

    void onMessageCategoriesUpdated(long userId, List<MessageCategory> categories) {
        messageCategories.put(userId, categories);
    }

    void onBlacklistUpdated(long userId, List<BlacklistEntry> blacklistEntries) {
        blacklist.put(userId, blacklistEntries);
    }
}
