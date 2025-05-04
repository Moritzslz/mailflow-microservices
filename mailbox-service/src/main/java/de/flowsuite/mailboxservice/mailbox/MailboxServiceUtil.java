package de.flowsuite.mailboxservice.mailbox;

import de.flowsuite.mailboxservice.exception.InvalidPortsException;
import de.flowsuite.mailboxservice.exception.InvalidSettingsException;
import de.flowsuite.mailflow.common.entity.Settings;

import java.util.ArrayList;
import java.util.List;

class MailboxServiceUtil {

    private static final ArrayList<Integer> VALID_IMAP_PORTS = new ArrayList<>(List.of(993));
    private static final ArrayList<Integer> VALID_SMTP_PORTS = new ArrayList<>(List.of(465, 587, 2525));

    static void validateUserSettings(long userId, Settings settings) {

        if (settings == null) {
            throw new InvalidSettingsException(userId);
        }

        if (settings.getImapHost() == null ||
                settings.getSmtpHost() == null ||
                settings.getImapPort() == null ||
                settings.getSmtpPort() == null) {
            throw new InvalidSettingsException(userId);
        }

        if (settings.getImapHost().isBlank() || settings.getSmtpHost().isBlank()) {
            throw new InvalidSettingsException(userId);
        }

        if (!VALID_IMAP_PORTS.contains(settings.getImapPort()) ||
                !VALID_SMTP_PORTS.contains(settings.getSmtpPort())) {
            throw new InvalidPortsException(userId, VALID_IMAP_PORTS.toString(), VALID_SMTP_PORTS.toString());
        }
    }
}
