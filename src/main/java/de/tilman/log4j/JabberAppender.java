/*
 * Copyright 2011 Tilman Walther
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 *      
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package de.tilman.log4j;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.spi.LoggingEvent;
import org.jivesoftware.smack.Chat;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.Message;



/**
 * A simple log4j appender that connects the logger to an XMPP recipient.  
 * 
 * @author Tilman Liero
 */
public class JabberAppender extends AppenderSkeleton {
	
	private final static Logger log = Logger.getLogger(JabberAppender.class);
	
	XMPPConnection connection;
	String recipient;
	Chat chat;
	
	public JabberAppender(String recipient, String server, String user, String password) throws XMPPException {
		this.setLayout(new PatternLayout("%m%n"));
		
		log.info("Connecting to " + server);
		ConnectionConfiguration jabberConfig = new ConnectionConfiguration(server);
		jabberConfig.setSendPresence(false);
		jabberConfig.setRosterLoadedAtLogin(false);
		connection = new XMPPConnection(jabberConfig);
		connection.connect();
		connection.login(user, password);

		chat = connection.getChatManager().createChat(recipient, new MessageListener() {
			@Override
			public void processMessage(Chat chat, Message message) {
				// nothing
			}
		});
	}

	@Override
	protected void append(LoggingEvent event) {
		sendChat(layout.format(event));
	}

	@Override
	public void close() {
		log.info("Closing XMPP connection");
		connection.disconnect();
	}

	@Override
	public boolean requiresLayout() {
		return false;
	}
	
	public void sendChat(String message) {
		try {
			chat.sendMessage(message);
		} catch (XMPPException xe) {
			log.error(xe.getMessage(), xe);
		}
	}

}
