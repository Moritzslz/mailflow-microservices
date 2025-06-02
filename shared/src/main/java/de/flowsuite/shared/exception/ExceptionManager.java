package de.flowsuite.shared.exception;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Properties;

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

    private final String applicationName;
    private final String username;
    private final String password;
    private final String host;
    private final String port;

    public ExceptionManager(
            @Value("${spring.application.name}") String applicationName,
            @Value("${mail.username}") String username,
            @Value("${mail.port}") String password,
            @Value("${mail.host}") String host,
            @Value("${mail.port}") String port) {
        this.applicationName = applicationName;
        this.username = username;
        this.password = password;
        this.host = host;
        this.port = port;
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
                LOG.error("Handled service exception (no admin notification required)", exception);
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
        Properties properties = new Properties();
        properties.put("mail.smtp.host", host);
        properties.put("mail.smtp.port", port);
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.ssl.enable", "true");
        properties.put("mail.smtp.starttls.enable", "true");

        Session session = Session.getInstance(properties);

        try (Transport transport = session.getTransport("smtps")) {

            transport.connect(host, username, password);

            Address address = new InternetAddress(username);
            MimeMessage notificationMessage = new MimeMessage(session);
            notificationMessage.setFrom(address);
            notificationMessage.setSubject(subject);
            notificationMessage.setContent(body, "text/html; charset=UTF-8");

            transport.sendMessage(notificationMessage, new Address[] {address});
        } catch (MessagingException e) {
            LOG.error("Failed to send email", e);
        }
    }
}
