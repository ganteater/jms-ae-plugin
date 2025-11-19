package com.ganteater.ae.processor;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.QueueBrowser;
import javax.jms.QueueConnection;
import javax.jms.QueueReceiver;
import javax.jms.QueueSender;
import javax.jms.QueueSession;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.commons.lang.mutable.MutableInt;

import com.ganteater.ae.processor.annotation.CommandExamples;
import com.ganteater.ae.util.xml.easyparser.Node;
import com.ibm.mq.jms.MQQueue;
import com.ibm.mq.jms.MQQueueConnectionFactory;

public class JMS extends BaseProcessor {

	private static final int ITERATION_TIMEOUT = 100;

	interface SessionOperation {
		public void operation(QueueSession session, Queue queue) throws JMSException;
	}

	@CommandExamples({ "<SendMessage name='type:property' queue='type:string' attr-map='type:property'/>",
			"<SendMessage name='type:property' queue='type:string' host='type:string' manager='type:string' channel='type:string'"
					+ " port='type:integer' user='type:string' password='type:string' targetClient='type:integer' cipherSuite='type:string'/>" })
	public void runCommandSendMessage(final Node action) throws NumberFormatException, JMSException {
		Object messageObj = attrValue(action, "name");

		executeJMSAction(action, (s, q) -> {
			try (QueueSender sender = s.createSender(q)) {
				if (messageObj instanceof String) {
					String message = (String) messageObj;
					TextMessage msg = s.createTextMessage(message);
					msg.setJMSDeliveryMode(DeliveryMode.PERSISTENT);
					sender.send(msg);
				} else if (messageObj instanceof String[]) {
					String[] messageArray = (String[]) messageObj;
					for (String message : messageArray) {
						TextMessage msg = s.createTextMessage(message);
						msg.setJMSDeliveryMode(DeliveryMode.PERSISTENT);
						sender.send(msg);
					}
				}
			}
		});

	}

	@CommandExamples({ "<ReceiveMessage name='type:property' queue='type:string'  attr-map='type:property' />",
			"<ReceiveMessage name='type:property' queue='type:string' attr-map='type:property' timeout='type:ms'/>",
			"<ReceiveMessage name='type:property' queue='type:string' host='type:string' manager='type:string' channel='type:string'"
					+ "port='type:integer' user='type:string' password='type:string' targetClient='type:integer' timeout='type:ms' type='enum:text|object|map|bytes' "
					+
					"transportType='type:integer' cipherSuite='type:string'/>" })
	public void runCommandReceiveMessage(final Node action) throws NumberFormatException, JMSException {
		String name = attr(action, "name");
		int timeout = (int) parseTime(action, "timeout", "0");

		executeJMSAction(action, (s, q) -> {
			try (QueueReceiver receive = s.createReceiver(q)) {
				Message message = null;

				long iterations = timeout > 0 ? timeout / ITERATION_TIMEOUT : Long.MAX_VALUE;
				iterations = iterations == 0 ? 1 : iterations;

				long tmo = timeout < ITERATION_TIMEOUT && timeout > 0 ? timeout : ITERATION_TIMEOUT;
				for (int i = 0; i < iterations && (message = receive.receive(tmo)) == null; i++) {
					if (isStopped()) {
						break;
					}
				}
				if (message != null) {
					Object body = getBody(action, message);
					setVariableValue(name, body);
				} else {
					setVariableValue(name, null);
				}
			}
		});
	}

	private Object getBody(final Node action, Message message) throws JMSException {
		Class bodyClass = getBodyClass(action);
		Object body = message.getBody(bodyClass);
		if (body instanceof TextMessage) {
			body = ((TextMessage) body).getText();
		} else if (body instanceof ObjectMessage) {
			body = ((ObjectMessage) body).getObject();
		} else if (body instanceof MapMessage) {
			MapMessage mapMessage = (MapMessage) body;
			Enumeration mapNames = mapMessage.getMapNames();
			Map resultMap = new HashMap<>();
			while (mapNames.hasMoreElements()) {
				String key = (String) mapNames.nextElement();
				Object value = mapMessage.getObject(key);
				resultMap.put(key, value);
			}
			body = resultMap;
		}
		return body;
	}

	private Class getBodyClass(final Node action) {
		String bodyClassName = attr(action, "type");
		Class bodyClass = null;
		if (bodyClassName != null) {
			switch (bodyClassName.toLowerCase()) {
			case "text":
				bodyClass = String.class;
				break;

			case "object":
				bodyClass = Object.class;
				break;

			case "map":
				bodyClass = Map.class;
				break;

			case "bytes":
				bodyClass = byte[].class;
				break;

			default:
				break;
			}

			if (bodyClass == null) {
				try {
					bodyClass = getClass().getClassLoader().loadClass(bodyClassName);

				} catch (ClassNotFoundException e) {
					log.error("Incorrect 'class' attribute: " + bodyClassName, e);
				}
			}
		} else {
			bodyClass = String.class;
		}
		return bodyClass;
	}

	@CommandExamples({ "<BrowseMessages name='type:property' queue='type:string'  attr-map='type:property'/>",
			"<BrowseMessages name='type:property' queue='type:string' host='type:string' manager='type:string' type='enum:text|object|map|bytes'"
					+ " channel='type:string' port='type:integer' user='type:string' password='type:string' targetClient='type:integer' cipherSuite='type:string'/>" })
	public void runCommandBrowseMessages(final Node action) throws NumberFormatException, JMSException {
		String name = attr(action, "name");
		List<Object> result = new ArrayList<>();
		executeJMSAction(action, (s, q) -> {
			try (QueueBrowser browser = s.createBrowser(q)) {
				Enumeration enumeration = browser.getEnumeration();
				while (enumeration.hasMoreElements() && !isStopped()) {
					Message message = (Message) enumeration.nextElement();
					Object body = getBody(action, message);
					result.add(body);
				}
				setVariableValue(name, result.isEmpty() ? null : result);
			}
		});
	}

	@CommandExamples({ "<MQSize name='type:property' queue='type:string'  attr-map='type:property'/>",
			"<MQSize name='type:property' queue='type:string' host='type:string' manager='type:string' "
					+ "channel='type:string' port='type:integer' user='type:string' password='type:string' cipherSuite='type:string'/>" })
	public void runCommandMQSize(final Node action) throws NumberFormatException, JMSException {
		String name = attr(action, "name");
		MutableInt result = new MutableInt();
		executeJMSAction(action, (s, q) -> {
			try (QueueBrowser browser = s.createBrowser(q)) {
				Enumeration enumeration = browser.getEnumeration();
				while (enumeration.hasMoreElements() && !isStopped()) {
					enumeration.nextElement();
					result.increment();
				}
				setVariableValue(name, result.getValue());
			}
		});
	}

	private void executeJMSAction(final Node action, SessionOperation operation) throws JMSException {
		MQQueueConnectionFactory factory = new MQQueueConnectionFactory();

		factory.setHostName(attr(action, "host"));
		factory.setPort(Integer.parseInt(attr(action, "port")));
		factory.setQueueManager(attr(action, "manager"));
		factory.setChannel(attr(action, "channel"));

		String ssl = attr(action, "cipherSuite");
		if (ssl != null) {
			factory.setSSLCipherSuite(ssl);
		}

		String transportType = attr(action, "transportType", "1");
		factory.setTransportType(Integer.parseInt(transportType));

		String appId = attr(action, "appId");
		if (appId == null) {
			String userName = getVariableString("USER_NAME");
			appId = "Anteater(" + userName + ")";
		}
		factory.setAppName(appId);

		String user = attr(action, "user");
		String password = attr(action, "password");
		String queueName = attr(action, "queue");

		String targetClient = attr(action, "targetClient");

		try (QueueConnection connection = factory.createQueueConnection(user, password);
				QueueSession session = connection.createQueueSession(false, Session.AUTO_ACKNOWLEDGE)) {

			Queue queue = session.createQueue(queueName);
			if (targetClient != null && queue instanceof MQQueue) {
				((MQQueue) queue).setTargetClient(Integer.parseInt(targetClient));
			}

			connection.start();
			operation.operation(session, queue);
			connection.stop();
		}
	}

}
