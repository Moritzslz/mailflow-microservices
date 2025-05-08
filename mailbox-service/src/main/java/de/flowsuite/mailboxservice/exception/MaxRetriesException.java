package de.flowsuite.mailboxservice.exception;

public class MaxRetriesException extends MailboxException {

    public MaxRetriesException(long userId, Throwable cause) {
        super(
                String.format("Max retries reached. Fully stopping execution for user %d", userId),
                cause,
                true);
    }
}
