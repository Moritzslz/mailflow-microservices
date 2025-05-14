package de.flowsuite.mailboxservice.mailbox;

import de.flowsuite.mailboxservice.exception.InvalidPortsException;
import de.flowsuite.mailboxservice.exception.InvalidSettingsException;
import de.flowsuite.mailflow.common.entity.Settings;

import jakarta.validation.Valid;

import java.util.List;

class MailboxServiceUtil {

    private static final List<Integer> VALID_IMAP_PORTS = List.of(993);
    private static final List<Integer> VALID_SMTP_PORTS = List.of(465, 587, 2525);

    static void validateUserSettings(long userId, @Valid Settings settings)
            throws InvalidSettingsException {

        if (settings == null
                || settings.getImapHost() == null
                || settings.getSmtpHost() == null
                || settings.getImapPort() == null
                || settings.getSmtpPort() == null
                || settings.getImapHost().isBlank()
                || settings.getSmtpHost().isBlank()) {
            throw new InvalidSettingsException(userId);
        }

        if (!VALID_IMAP_PORTS.contains(settings.getImapPort())
                || !VALID_SMTP_PORTS.contains(settings.getSmtpPort())) {
            throw new InvalidPortsException(
                    userId, VALID_IMAP_PORTS.toString(), VALID_SMTP_PORTS.toString());
        }
    }
}
