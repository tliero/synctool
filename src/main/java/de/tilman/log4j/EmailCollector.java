package de.tilman.log4j;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Properties;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;

public class EmailCollector extends AppenderSkeleton {
	
	private final static Logger log = Logger.getLogger(EmailCollector.class);
	
	/**
	 * Maximum number of lines to collect
	 */
	private int bufferSize;
	
	private LinkedList<String> lineBuffer;
	
	private String smtpUser;
	private String smtpPassword;
	private String emailRecipient;
	private String emailSubject;
	
	private SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS");
	
	public EmailCollector(int bufferSize, String smtpUser, String smtpPassword, String emailRecipient, String emailSubject) {
		this.setName("EmailCollector");
		this.bufferSize = bufferSize;
		lineBuffer = new LinkedList<String>();
		this.smtpUser = smtpUser;
		this.smtpPassword = smtpPassword;
		this.emailRecipient = emailRecipient;
		this.emailSubject = emailSubject;
	}

	/**
	 * Close the EmailCollector and send collected messages.
	 * 
	 * @see org.apache.log4j.Appender#close()
	 */
	@Override
	public void close() {
		log.info("Sending e-mail report to " + emailRecipient);
		
		String host = "smtp.gmail.com";
		Properties props = System.getProperties();
		props.put("mail.smtp.starttls.enable", "true");
		props.put("mail.smtp.host", host);
		props.put("mail.smtp.user", smtpUser);
		props.put("mail.smtp.password", smtpPassword);
		props.put("mail.smtp.port", "587");
		props.put("mail.smtp.auth", "true");
		
		Session session = Session.getDefaultInstance(props, null);
		MimeMessage message = new MimeMessage(session);
		try {
			message.setFrom(new InternetAddress(smtpUser));
			message.addRecipient(Message.RecipientType.TO, new InternetAddress(emailRecipient));
			message.setSubject(emailSubject);
			
			StringBuffer sb = new StringBuffer();
			while (lineBuffer.size() > 0) {
				sb.append(lineBuffer.poll());
				sb.append("\n");
			}
			
			message.setText(sb.toString());
			Transport transport = session.getTransport("smtp");
			transport.connect(host, smtpUser, smtpPassword);
			transport.sendMessage(message, message.getAllRecipients());
			transport.close();
		} catch (MessagingException e) {
			log.error("Error sending e-mail", e);
		}
		
		this.closed = true;
	}

	@Override
	public boolean requiresLayout() {
		return false;
	}

	@Override
	protected void append(LoggingEvent event) {
		if (lineBuffer.size() >= bufferSize) {
			lineBuffer.removeFirst();
		}
		lineBuffer.add(formatter.format(new Date(event.getTimeStamp())) + " - " + event.getRenderedMessage());
	}

}
