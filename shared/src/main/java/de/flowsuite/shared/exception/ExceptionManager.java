package de.flowsuite.shared.exception;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class ExceptionManager {

    private static final Logger LOG = LoggerFactory.getLogger(ExceptionManager.class);

    private static final String MAIL_SUBJECT = "Exception Alert: %s";
    private static final String MAIL_HTML_TEMPLATE =
            """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            	<meta charset="UTF-8">
            	<meta name="viewport" content="width=device-width, initial-scale=1.0">
            	<title>Exception Notification</title>
            </head>
            <body>
            	<div>
            		<h1>Exception Alert: %s</h1>
            		<p><strong>Exception Type:</strong> %s</p>
            		<p><strong>Message:</strong> %s</p>

            		<div>
            			<p><strong>Stack Trace:</strong></p>
            			<pre>%s</pre>
            		</div>

            		<footer>
            			This is an automated message. Please investigate the issue.
            		</footer>
            	</div>
            </body>
            </html>
            """;

    private final JavaMailSender mailSender;
    private final String applicationName;
    private final String emailAddress;

    public ExceptionManager(
            JavaMailSender mailSender,
            @Value("${spring.application.name}") String applicationName,
            @Value("${spring.mail.username}") String emailAddress) {
        this.mailSender = mailSender;
        this.applicationName = applicationName;
        this.emailAddress = emailAddress;
    }

    public void handleException(Exception e) {
        handleException(e, true);
    }

    public void handleException(Exception e, boolean log) {
        if (e instanceof ServiceException exception) {
            if (exception.shouldNotifyAdmin()) {
                if (log) {
                    LOG.error("Service exception occurred. Notifying admin. Error:", exception);
                }
                notifyAdmin(e);
            } else if (log) {
                LOG.error(
                        "Handled service exception (no admin notification required): {}",
                        exception.getMessage());
            }
        } else {
            if (log) {
                LOG.error("Unexpected exception occurred. Notifying admin. Error:", e);
            }
            notifyAdmin(e);
        }
    }

    private void notifyAdmin(Exception e) {
        LOG.debug("Sending notification email to admin");

        StringBuilder stackTraceHtmlBuilder = new StringBuilder();

        Throwable exception = e;
        int level = 0;

        while (exception != null) {
            stackTraceHtmlBuilder
                    .append("<p><strong>")
                    .append("  ".repeat(Math.max(0, level)))
                    .append(level == 0 ? "Exception: " : "Caused by: ")
                    .append(exception.getClass().getSimpleName())
                    .append(" — ")
                    .append(exception.getMessage())
                    .append("</strong></p>");

            stackTraceHtmlBuilder.append("<div>");
            for (StackTraceElement element : exception.getStackTrace()) {
                stackTraceHtmlBuilder
                        .append("  ".repeat(Math.max(0, level)))
                        .append(element.toString())
                        .append("<br>");
            }
            stackTraceHtmlBuilder.append("</div>");

            exception = exception.getCause();
            level++;
        }

        String subject = String.format(MAIL_SUBJECT, applicationName);

        String body =
                String.format(
                        MAIL_HTML_TEMPLATE,
                        applicationName,
                        e.getClass().getSimpleName(),
                        e.getMessage(),
                        stackTraceHtmlBuilder);

        sendEmail(body, subject);
    }

    private void sendEmail(String body, String subject) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();

            MimeMessageHelper mimeMessageHelper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            mimeMessageHelper.setText(body, true);
            mimeMessageHelper.setTo(emailAddress);
            mimeMessageHelper.setSubject(subject);
            mimeMessageHelper.setFrom(emailAddress);

            mailSender.send(mimeMessage);

            LOG.debug("Email sent successfully.");
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
    }
}
