package de.flowsuite.mailboxservice.message;

import org.springframework.stereotype.Service;

@Service
class MessageService {

    private final MessageProcessor processor;
    private final MessageResponseHandler responseHandler;

    public MessageService(MessageProcessor processor, MessageResponseHandler responseHandler) {
        this.processor = processor;
        this.responseHandler = responseHandler;
    }

    public void processMessage(Message message) {
        this.processor.processMessage(message, this.responseHandler);
    }

}
