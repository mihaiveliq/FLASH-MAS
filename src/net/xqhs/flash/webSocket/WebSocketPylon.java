/*******************************************************************************
 * Copyright (C) 2021 Andrei Olaru.
 * 
 * This file is part of Flash-MAS. The CONTRIBUTORS.md file lists people who have been previously involved with this project.
 * 
 * Flash-MAS is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or any later version.
 * 
 * Flash-MAS is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with Flash-MAS.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package net.xqhs.flash.webSocket;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import net.xqhs.flash.core.DeploymentConfiguration;
import net.xqhs.flash.core.agent.AgentWave;
import net.xqhs.flash.core.shard.AgentShardDesignation;
import net.xqhs.flash.core.support.DefaultPylonImplementation;
import net.xqhs.flash.core.support.MessageReceiver;
import net.xqhs.flash.core.support.MessagingPylonProxy;
import net.xqhs.flash.core.support.Pylon;
import net.xqhs.flash.core.util.MultiTreeMap;
import net.xqhs.flash.core.util.PlatformUtils;

/**
 * WebSocket support implementation that allows agents to send messages whether they are inside the same JVM or not. The
 * pylon is connected to a {@link WebSocketServerEntity}. Therefore any agent in the context of this support is able to
 * send messages to agents located on any other pylon connected to the same Websocket server.
 *
 * @author Florina Nastasoiu
 * @author Andrei Olaru
 */
public class WebSocketPylon extends DefaultPylonImplementation {
	
	/**
	 * The tread that will manage messages.
	 * 
	 * @author Andrei Olaru
	 */
	class MessageThread implements Runnable {
		@Override
		public void run() {
			while(useThread) {
				if(messageQueue.isEmpty())
					try {
						synchronized(messageQueue) {
							messageQueue.wait();
						}
					} catch(InterruptedException e) {
						// do nothing
					}
				else {
					Map.Entry<WebSocketMessagingShard, Vector<String>> event = messageQueue.poll();
					event.getKey().receiveMessage(event.getValue().get(0), event.getValue().get(1),
							event.getValue().get(2));
				}
			}
		}
	}
	
	/**
	 * The key in the JSON object which is assigned to the source of the message.
	 */
	public static final String											MESSAGE_SOURCE_KEY				= "source";
	/**
	 * The key in the JSON object which is assigned to the name of the node on which an entity executes (for
	 * registration messages).
	 */
	public static final String											MESSAGE_NODE_KEY				= "nodeName";
	/**
	 * The key in the JSON object which is assigned to the name of the entity (for registration messages).
	 */
	public static final String											MESSAGE_ENTITY_KEY				= "entityName";
	/**
	 * The key in the JSON object which is assigned to the destination of the message.
	 */
	public static final String											MESSAGE_DESTINATION_KEY			= "destination";
	/**
	 * If this key is present, the entity will be unregistered.
	 */
	public static final String											UNREGISTER_KEY					= "unregister";
	/**
	 * The key in the JSON object which is assigned to the content of the message.
	 */
	public static final String											MESSAGE_CONTENT_KEY				= "content";
	
	/**
	 * The proxy to this pylon, to be referenced by any entities in the scope of this pylon.
	 */
	public MessagingPylonProxy											messagingProxy					= null;
	
	/**
	 * The attribute name of server address of this instance.
	 */
	public static final String											WEBSOCKET_SERVER_ADDRESS_NAME	= "connectTo";
	/**
	 * The attribute name for the server port.
	 */
	public static final String											WEBSOCKET_SERVER_PORT_NAME		= "serverPort";
	/**
	 * The prefix for Websocket server address.
	 */
	public static final String											WS_PROTOCOL_PREFIX				= "ws://";
	
	/**
	 * <code>true</code> if there is a Websocket server configured on the local node.
	 */
	protected boolean													hasServer;
	
	/**
	 * For the case in which a server must be created on this node, the port the server is bound to.
	 */
	protected int														serverPort						= -1;
	/**
	 * For the case in which a server must be created on this node, the entity that represents the server.
	 */
	protected WebSocketServerEntity										serverEntity;
	
	/**
	 * The address of the Websocket server that the client should connect to.
	 */
	protected String													webSocketServerAddress;
	/**
	 * The {@link WebSocketClient} instance to use.
	 */
	protected WebSocketClient											webSocketClient;
	/**
	 * For the entities in the scope of this pylon, the correspondence between their names and their
	 * {@link MessageReceiver} instances.
	 */
	protected HashMap<String, MessageReceiver>							messageReceivers				= new HashMap<>();
	
	/**
	 * If <code>true</code>, a separate thread will be used to buffer messages. Otherwise, only method calling will be
	 * used.
	 * <p>
	 * If a thread is used, {@link MessagingPylonProxy#send(String, String, String)} will always return true.
	 * <p>
	 * <b>WARNING:</b> not using a thread may lead to race conditions and deadlocks. Use only if you know what you are
	 * doing.
	 */
	protected boolean													useThread						= true;
	/**
	 * The queue of messages to process to be processed by the {@link #messageThread}.
	 */
	protected Queue<Map.Entry<WebSocketMessagingShard, Vector<String>>>	messageQueue;
	/**
	 * The thread processing the messages in the {@link #messageQueue}.
	 */
	protected Thread													messageThread;
	
	/**
	 * The constructor, with the mission of building the {@link MessagingPylonProxy}.
	 */
	public WebSocketPylon() {
		messagingProxy = new MessagingPylonProxy() {
			/**
			 * The entity is both: - registered within the local instance which is useful for routing a message back to
			 * the the {@link MessageReceiver} instance when it arrives from the server - registered to the
			 * {@link WebSocketServerEntity} using an entity registration format message which is sent by the local
			 * client
			 *
			 * @param entityName
			 *            - the name of the entity
			 * @param receiver
			 *            - the {@link MessageReceiver} instance to receive messages
			 * @return - an indication of success
			 */
			@Override
			@SuppressWarnings("unchecked")
			public boolean register(String entityName, MessageReceiver receiver) {
				if(messageReceivers.containsKey(entityName))
					return ler(false, "Entity [] already registered with this pylon [].", entityName, thisPylon());
				messageReceivers.put(entityName, receiver);
				JSONObject messageToServer = new JSONObject();
				messageToServer.put(MESSAGE_NODE_KEY, getNodeName());
				messageToServer.put(MESSAGE_ENTITY_KEY, entityName);
				webSocketClient.send(messageToServer.toString());
				lf("Registered entity []/[] with this pylon []: ", entityName, receiver, thisPylon(), messageToServer);
				return true;
			}
			
			@Override
			public boolean unregister(String entityName, MessageReceiver registeredReceiver) {
				if(!messageReceivers.remove(entityName, registeredReceiver))
					return false;
				JSONObject messageToServer = new JSONObject();
				messageToServer.put(MESSAGE_NODE_KEY, getNodeName());
				messageToServer.put(MESSAGE_ENTITY_KEY, entityName);
				messageToServer.put(UNREGISTER_KEY, UNREGISTER_KEY);
				webSocketClient.send(messageToServer.toString());
				lf("Unregistered entity [] from this pylon []: ", entityName, thisPylon(), messageToServer);
				return true;
			}
			
			/**
			 * Send a message to the server.
			 *
			 * @param source
			 *            - the source endpoint
			 * @param destination
			 *            - the destination endpoint
			 * @param content
			 *            - the content of the message
			 * @return - an indication of success
			 */
			@Override
			@SuppressWarnings("unchecked")
			public boolean send(String source, String destination, String content) {
				if(messageReceivers.containsKey(destination)) {
					messageReceivers.get(destination).receive(source, destination, content);
					return true;
				}
				JSONObject messageToServer = new JSONObject();
				messageToServer.put(MESSAGE_NODE_KEY, getNodeName());
				messageToServer.put(MESSAGE_SOURCE_KEY, source);
				messageToServer.put(MESSAGE_DESTINATION_KEY, destination);
				messageToServer.put(MESSAGE_CONTENT_KEY, content);
				webSocketClient.send(messageToServer.toString());
				return true;
			}
			
			@Override
			public String getRecommendedShardImplementation(AgentShardDesignation shardType) {
				return WebSocketPylon.this.getRecommendedShardImplementation(shardType);
			}
			
			@Override
			public String getEntityName() {
				return getName();
			}
		};
	}
	
	/**
	 * Starts the {@link WebSocketServerEntity} if the pylon was delegated from the deployment. Now the entity is ready
	 * to send and receive messages.
	 *
	 * @return an indication of success.
	 */
	@Override
	public boolean start() {
		if(hasServer) {
			serverEntity = new WebSocketServerEntity(serverPort);
			serverEntity.start();
		}
		
		try {
			int tries = 10;
			long spaceBetweenTries = 1000;
			while(tries > 0) {
				try {
					lf("Trying connection to WS server [] tries left: []", webSocketServerAddress,
							Integer.valueOf(tries));
					webSocketClient = new WebSocketClient(new URI(webSocketServerAddress)) {
						@Override
						public void onOpen(ServerHandshake serverHandshake) {
							lf("connected to []", getURI());
						}
						
						/**
						 * Receives a message from the server. The message was previously routed to this websocket
						 * client address and it is further routed to a specific entity using the
						 * {@link MessageReceiver} instance. The entity is searched within the context of this support.
						 *
						 * @param s
						 *            - the JSON string containing a message and routing information
						 */
						@Override
						public void onMessage(String s) {
							Object obj = JSONValue.parse(s);
							if(obj == null) {
								le("null message received");
								return;
							}
							JSONObject jsonObject = (JSONObject) obj;
							
							if(jsonObject.get("destination") == null) {
								le("No destination entity received.");
								return;
							}
							String destination = (String) jsonObject.get("destination");
							String localAddr = destination.split(AgentWave.ADDRESS_SEPARATOR)[0];
							if(!messageReceivers.containsKey(localAddr) || messageReceivers.get(localAddr) == null)
								le("Entity [] does not exist in the scope of this pylon [].", localAddr, thisPylon());
							else {
								String source = (String) jsonObject.get("source");
								String content = (String) jsonObject.get("content");
								messageReceivers.get(localAddr).receive(source, destination, content);
							}
						}
						
						@Override
						public void onClose(int i, String s, boolean b) {
							lw("Closed with exit code " + i);
						}
						
						@Override
						public void onError(Exception e) {
							le(Arrays.toString(e.getStackTrace()));
						}
					};
				} catch(URISyntaxException e) {
					e.printStackTrace();
					return false;
				}
				if(webSocketClient.connectBlocking())
					break;
				Thread.sleep(spaceBetweenTries);
				tries--;
			}
		} catch(InterruptedException e) {
			e.printStackTrace();
			return false;
		}
		
		if(!super.start())
			return false;
		if(useThread) {
			messageQueue = new LinkedBlockingQueue<>();
			messageThread = new Thread(new MessageThread());
			messageThread.start();
		}
		li("Started" + (useThread ? " [with thread]." : "[without thread]"));
		return true;
	}
	
	@Override
	public boolean stop() {
		super.stop();
		if(useThread) {
			useThread = false; // signal to the thread
			synchronized(messageQueue) {
				messageQueue.clear();
				messageQueue.notifyAll();
			}
			try {
				messageThread.join();
			} catch(InterruptedException e) {
				e.printStackTrace();
			}
			messageQueue = null;
			messageThread = null;
		}
		if(hasServer)
			serverEntity.stop();
		try {
			webSocketClient.closeBlocking();
		} catch(InterruptedException x) {
			x.printStackTrace();
		}
		li("Stopped");
		return true;
	}
	
	@Override
	public boolean configure(MultiTreeMap configuration) {
		if(!super.configure(configuration))
			return false;
		if(configuration.isSimple(WEBSOCKET_SERVER_PORT_NAME)) {
			hasServer = true;
			serverPort = Integer.parseInt(configuration.getAValue(WEBSOCKET_SERVER_PORT_NAME));
			webSocketServerAddress = WS_PROTOCOL_PREFIX + PlatformUtils.getLocalHostURI() + ":" + serverPort;
		}
		else if(configuration.isSimple(WEBSOCKET_SERVER_ADDRESS_NAME))
			webSocketServerAddress = configuration.getAValue(WEBSOCKET_SERVER_ADDRESS_NAME);
		if(configuration.isSimple(DeploymentConfiguration.NAME_ATTRIBUTE_NAME))
			name = configuration.get(DeploymentConfiguration.NAME_ATTRIBUTE_NAME);
		return true;
	}
	
	@Override
	public String getRecommendedShardImplementation(AgentShardDesignation shardName) {
		if(shardName.equals(AgentShardDesignation.standardShard(AgentShardDesignation.StandardAgentShard.MESSAGING)))
			return WebSocketMessagingShard.class.getName();
		return super.getRecommendedShardImplementation(shardName);
	}
	
	/**
	 * @return the name of the local node, as configured in {@link DefaultPylonImplementation}.
	 */
	protected String getNodeName() {
		return nodeName;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public EntityProxy<Pylon> asContext() {
		return messagingProxy;
	}
	
	@Override
	protected void le(String message, Object... arguments) {
		super.le(message, arguments);
	}
	
	@Override
	protected void lw(String message, Object... arguments) {
		super.lw(message, arguments);
	}
	
	@Override
	protected void li(String message, Object... arguments) {
		super.li(message, arguments);
	}
	
	@Override
	protected void lf(String message, Object... arguments) {
		super.lf(message, arguments);
	}
	
	@Override
	protected boolean ler(boolean ret, String message, Object... arguments) {
		return super.ler(ret, message, arguments);
	}
	
	/**
	 * @return this pylon, to be provided to embedded instances.
	 */
	WebSocketPylon thisPylon() {
		return this;
	}
}
