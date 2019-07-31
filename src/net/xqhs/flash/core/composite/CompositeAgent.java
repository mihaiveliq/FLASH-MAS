/*******************************************************************************
 * Copyright (C) 2018 Andrei Olaru.
 * 
 * This file is part of Flash-MAS. The CONTRIBUTORS.md file lists people who have been previously involved with this project.
 * 
 * Flash-MAS is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or any later version.
 * 
 * Flash-MAS is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with Flash-MAS.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package net.xqhs.flash.core.composite;

import java.io.Serializable;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import net.xqhs.flash.core.DeploymentConfiguration;
import net.xqhs.flash.core.Entity;
import net.xqhs.flash.core.agent.Agent;
import net.xqhs.flash.core.agent.AgentEvent;
import net.xqhs.flash.core.agent.AgentEvent.AgentEventType;
import net.xqhs.flash.core.agent.AgentEvent.AgentSequenceType;
import net.xqhs.flash.core.shard.AgentShardCore;
import net.xqhs.flash.core.shard.AgentShardDesignation;
import net.xqhs.flash.core.shard.ShardContext;
import net.xqhs.flash.core.support.Pylon;
import net.xqhs.flash.core.util.MultiTreeMap;
import net.xqhs.flash.core.util.PlatformUtils;
import net.xqhs.util.logging.LoggerSimple.Level;
import net.xqhs.util.logging.UnitComponent;

/**
 * This class reunites the features of an agent in order for features to be able to call each other and for events to be
 * distributed to all features.
 * <p>
 * Various agent features -- instances of {@link AgentShardCore} -- can be added. Features are identified by means of
 * their designation. At most one feature with the same designation is allowed (i.e. at most one feature per
 * functionality).
 * <p>
 * It is this class that handles agent events, by means of the <code>postAgentEvent()</code> method, which disseminates
 * an event to all features, which handle it by means of registered handles (each feature registers a handle for an
 * event with itself). See {@link AgentShardCore}.
 * 
 * @author Andrei Olaru
 * 
 */
public class CompositeAgent implements Serializable, Agent
{
	static class CompositeAgentAsContext implements ShardContext
	{
		CompositeAgent agent = null;
		
		protected CompositeAgentAsContext(CompositeAgent agent)
		{
			this.agent = agent;
		}
		
		@Override
		public void postAgentEvent(AgentEvent event)
		{
			agent.postAgentEvent(event);
		}

		@Override
		public List<Context<Pylon>> getPylons() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public String getAgentName() {
			return agent.getName();
		}
	}
	
	/**
	 * Values indicating the current state of the agent, especially with respect to processing events.
	 * <p>
	 * The normal transition between states is the following: <br/>
	 * <ul>
	 * <li>{@link #STOPPED} [here features are normally added] + {@link AgentEventType#AGENT_START} &rarr;
	 * {@link #STARTING} [starting thread; starting features] &rarr; {@link #RUNNING}.
	 * <li>while in {@link #RUNNING}, features can be added or removed.
	 * <li>{@link #RUNNING} + {@link AgentEventType#AGENT_STOP} &rarr; {@link #STOPPING} [no more events accepted; stop
	 * features; stop thread] &rarr; {@link #STOPPED}.
	 * <li>when the {@link #TRANSIENT} state is involved, the transitions are as follows: {@link #RUNNING} +
	 * {@link AgentEventType#AGENT_STOP} w/ parameter {@link CompositeAgent#TRANSIENT_EVENT_PARAMETER} &rarr;
	 * {@link #STOPPING} &rarr {@link #TRANSIENT} [unable to modify agent] + {@link AgentEventType#AGENT_START} w/
	 * parameter {@link CompositeAgent#TRANSIENT_EVENT_PARAMETER} &rarr; {@link #RUNNING}.
	 * </ul>
	 * 
	 * @author Andrei Olaru
	 */
	enum AgentState {
		/**
		 * State indicating that the agent is currently behaving normally and agent events are processed in good order.
		 * All features are running.
		 */
		RUNNING,
		
		/**
		 * State indicating that the agent is stopped and is unable to process events. The agent's thread is stopped.
		 * All features are stopped.
		 */
		STOPPED,
		
		/**
		 * This state is a version of the {@link #STOPPED} state, with the exception that it does not allow any changes
		 * the general state of the agent (e.g. feature list). The state should be used to "freeze" the agent, such as
		 * for it to be serialized.. Normally, in this state features should not allow any changes either.
		 */
		TRANSIENT,
		
		/**
		 * State indicating that the agent is in the process of starting, but is not currently accepting events. The
		 * thread may or may not have been started. The features are in the process of starting.
		 */
		STARTING,
		
		/**
		 * State indicating that the agent is currently stopping. It is not accepting events any more. The thread may or
		 * may not be running. The features are in the process of stopping.
		 */
		STOPPING,
	}
	
	/**
	 * This is the event-processing thread of the agent.
	 * 
	 * @author Andrei Olaru
	 */
	class AgentThread implements Runnable
	{
		@Override
		public void run()
		{
			boolean threadExit = false;
			while(!threadExit)
			{
				// System.out.println("oops");
				if((eventQueue != null) && eventQueue.isEmpty())
					try
					{
						synchronized(eventQueue)
						{
							eventQueue.wait();
						}
					} catch(InterruptedException e)
					{
						// do nothing
					}
				else
				{
					AgentEvent event = eventQueue.poll();
					switch(event.getType().getSequenceType())
					{
					case CONSTRUCTIVE:
					case UNORDERED:
						for(AgentShardCore feature : featureOrder)
							feature.signalAgentEvent(event);
						break;
					case DESTRUCTIVE:
						for(ListIterator<AgentShardCore> it = featureOrder.listIterator(featureOrder.size()); it
								.hasPrevious();)
							it.previous().signalAgentEvent(event);
						break;
					default:
						throw new IllegalStateException(
								"Unsupported sequence type: " + event.getType().getSequenceType().toString());
					}
					
					threadExit = FSMEventOut(event.getType(), event.isSet(TRANSIENT_EVENT_PARAMETER));
				}
			}
		}
	}
	
	/**
	 * The class UID
	 */
	private static final long								serialVersionUID			= -2693230015986527097L;
	
	/**
	 * The name of the parameter that should be added to {@link AgentEventType#AGENT_START} /
	 * {@link AgentEventType#AGENT_STOP} events in order to take the agent out of / into the <code>TRANSIENT</code>
	 * state.
	 */
	public static final String								TRANSIENT_EVENT_PARAMETER	= "TO_FROM_TRANSIENT";
	
	/**
	 * This can be used by support implementation-specific features to contact the support implementation.
	 */
	protected Object										supportLink					= null;
	
	protected Context<Agent>								asContext					= new CompositeAgentAsContext(
			this);
	
	/**
	 * The {@link Map} that links feature designations (functionalities) to feature instances.
	 */
	protected Map<AgentShardDesignation, AgentShardCore>	features					= new HashMap<>();
	/**
	 * A {@link List} that holds the order in which features were added, so as to signal agent events to features in the
	 * correct order (as specified by {@link AgentSequenceType}).
	 * <p>
	 * It is important that this list is managed together with {@link #features}.
	 */
	protected ArrayList<AgentShardCore>						featureOrder				= new ArrayList<>();
	
	/**
	 * A synchronized queue of agent events, as posted by the features.
	 */
	protected LinkedBlockingQueue<AgentEvent>				eventQueue					= null;
	/**
	 * The thread managing the agent's life-cycle (managing events).
	 */
	protected Thread										agentThread					= null;
	/**
	 * The agent state. See {@link AgentState}. Access to this member should be synchronized with the lock of
	 * <code>eventQueue</code>.
	 */
	protected AgentState									agentState					= AgentState.STOPPED;
	
	/**
	 * The agent name, if given.
	 */
	protected String										agentName					= null;
	/**
	 * <b>*EXPERIMENTAL*</b>. This log is used only for important logging messages related to the agent's state. While
	 * the agent will attempt to use its set name, this may not always succeed. This log should only be used by means of
	 * the {@link #log(String, Object...)} method.
	 */
	protected UnitComponent									localLog					= (UnitComponent) new UnitComponent()
			.setLoggerType(PlatformUtils.platformLogType()).setLogLevel(Level.INFO);
	/**
	 * This switch activates the use of the {@link #localLog}.
	 */
	protected boolean										USE_LOCAL_LOG				= true;
	
	/**
	 * Constructor for {@link CompositeAgent} instances.
	 * <p>
	 * Although the name may be null, it is strongly recommended that the agent is given a (unique) name, even one that
	 * is automatically generated.
	 * 
	 * @param configuration
	 *            - the configuration, from which the name of the agent will be taken..
	 */
	public CompositeAgent(MultiTreeMap configuration)
	{
		agentName = configuration.get(DeploymentConfiguration.NAME_ATTRIBUTE_NAME);
	}
	
	/**
	 * Starts the life-cycle of the agent. All features will receive an {@link AgentEventType#AGENT_START} event.
	 * 
	 * @return true if the event has been successfully posted. See <code>postAgentEvent()</code>.
	 */
	@Override
	public boolean start()
	{
		return postAgentEvent(new AgentEvent(AgentEventType.AGENT_START));
	}
	
	/**
	 * Instructs the agent to unload all features and exit. All features will receive an
	 * {@link AgentEventType#AGENT_STOP} event.
	 * <p>
	 * No events will be successfully received after this event has been posted.
	 * 
	 * @return true if the event has been successfully posted. See <code>postAgentEvent()</code>.
	 */
	public boolean exit()
	{
		return postAgentEvent(new AgentEvent(AgentEventType.AGENT_STOP));
	}
	
	/**
	 * Alias for {@link #exit()}.
	 */
	@Override
	public boolean stop()
	{
		return exit();
	}
	
	/**
	 * Instructs the agent to switch state between <code>STOPPED</code> and <code>TRANSIENT</code>.
	 * 
	 * @return <code>true</code> if the agent is now in the <code>TRANSIENT</code> state, <code>false</code> otherwise.
	 * 
	 * @throws RuntimeException
	 *             if the agent was in any other state than the two.
	 */
	public boolean toggleTransient() throws RuntimeException
	{
		return FSMToggleTransient();
	}
	
	/**
	 * Context can be added to an agent only when it is not running.
	 */
	@Override
	public boolean addContext(Context<Pylon> link)
	{
		if(!canAddFeatures() || isRunning())
			return false;
		supportLink = link;
		return true;
	}
	
	@Override
	public boolean addGeneralContext(Context<Entity<?>> context)
	{
		log("No general context supported.");
		return false;
	}
	
	/**
	 * Context can be removed from an agent only when it is not running.
	 */
	@Override
	public boolean removeContext(Context<Pylon> link)
	{
		if(!canAddFeatures() || isRunning())
			return false;
		supportLink = null;
		return true;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public Context<Agent> asContext()
	{
		return asContext;
	}
	
	/**
	 * The method should be called by an agent feature (relayed through {@link AgentShardCore}) to disseminate a an
	 * {@link AgentEvent} to the other features.
	 * <p>
	 * If the event has been successfully posted, the method returns <code>true</code>, guaranteeing that, except in the
	 * case of abnormal termination, the event will be processed eventually. Otherwise, it returns <code>false</code>,
	 * indicating that either the agent has not been started, or has been instructed to exit, or is in another
	 * inappropriate state.
	 * 
	 * @param event
	 *            the event to disseminate.
	 * @return <code>true</code> if the event has been successfully posted; <code>false</code> otherwise.
	 */
	protected boolean postAgentEvent(AgentEvent event)
	{
		event.lock();
		
		if(!canPostEvent(event))
			return false;
		
		AgentState futureState = FSMEventIn(event.getType(), event.isSet(TRANSIENT_EVENT_PARAMETER));
		
		try
		{
			if(eventQueue != null)
				synchronized(eventQueue)
				{
					if(futureState != null)
						agentState = futureState;
					eventQueue.put(event);
					eventQueue.notify();
				}
			else
				return false;
		} catch(InterruptedException e)
		{
			e.printStackTrace();
			return false;
		}
		return true;
	}
	
	/**
	 * Checks whether the specified event can be posted in the current agent state.
	 * <p>
	 * Basically, there are two checks:
	 * <ul>
	 * <li>Any event except {@link AgentEventType#AGENT_START} can be posted only in the {@link AgentState#RUNNING}
	 * state.
	 * <li>If the {@link AgentEventType#AGENT_START} is posted while the agent is in the {@link AgentState#TRANSIENT}
	 * state, it needs to feature a parameter called {@value #TRANSIENT_EVENT_PARAMETER} (with any value).
	 * <li>The {@link AgentEventType#AGENT_START} event can be posted while the agent is {@link AgentState#STOPPED}.
	 * 
	 * @param event
	 *            - the event one desires to post.
	 * @return <code>true</code> if the event could be posted at this moment; <code>false</code> otherwise.
	 */
	protected boolean canPostEvent(AgentEvent event)
	{
		switch(event.getType())
		{
		case AGENT_START:
			if(agentState == AgentState.TRANSIENT)
				return event.isSet(TRANSIENT_EVENT_PARAMETER);
			return agentState == AgentState.STOPPED;
		default:
			return agentState == AgentState.RUNNING;
		}
	}
	
	/**
	 * Change the state of the agent (if it is the case) and perform other actions, <i>before</i> an event is added to
	 * the event queue. It is presumed that the event has already been checked with {@link #canPostEvent(AgentEvent)}
	 * and that the event can indeed be posted to the queue in the current state.
	 * <p>
	 * If the event was {@link AgentEventType#AGENT_START}, the agent will enter {@link AgentState#STARTING}, the event
	 * queue is created and the agent thread is started.
	 * <p>
	 * If the event was {@link AgentEventType#AGENT_STOP}, the agent will enter {@link AgentState#STOPPING}.
	 * 
	 * @param eventType
	 *            - the type of the event.
	 * @param fromToTransient
	 *            - <code>true</code> if the agent should enter / exit from the {@link AgentState#TRANSIENT} state.
	 * @return the state the agent should enter next (the actual state change will happen in
	 *         {@link #postAgentEvent(AgentEvent)}, together with posting the event to the queue.
	 */
	protected AgentState FSMEventIn(AgentEventType eventType, boolean fromToTransient)
	{
		AgentState futureState = null;
		switch(eventType)
		{
		case AGENT_START:
			futureState = AgentState.STARTING;
			
			if(eventQueue != null)
				log("event queue already present");
			eventQueue = new LinkedBlockingQueue<>();
			agentThread = new Thread(new AgentThread());
			agentThread.start();
			break;
		case AGENT_STOP:
			futureState = AgentState.STOPPING;
			break;
		default:
			// nothing to do
		}
		if(futureState != null)
			log("Agent state is soon [][]", futureState, fromToTransient ? "transient" : "");
		return futureState;
	}
	
	/**
	 * Change the state of the agent (if it is the case) and perform other actions, <i>after</i> an event has been
	 * processed by all features.
	 * <p>
	 * If the event was {@link AgentEventType#AGENT_START}, the state will be {@link AgentState#RUNNING}.
	 * <p>
	 * If the event was {@link AgentEventType#AGENT_STOP}, the event queue will be consumed, the state will be
	 * {@link AgentState#STOPPED} or {@link AgentState#TRANSIENT} (depending on the event parameters), and the log and
	 * thread will exit.
	 * 
	 * @param eventType
	 *            - the type of the event.
	 * @param toFromTransient
	 *            - <code>true</code> if the agent should enter / exit from the {@link AgentState#TRANSIENT} state.
	 * @return <code>true</code> if the agent thread should exit.
	 */
	protected boolean FSMEventOut(AgentEventType eventType, boolean toFromTransient)
	{
		switch(eventType)
		{
		case AGENT_START: // the agent has completed starting and all features are up.
			synchronized(eventQueue)
			{
				agentState = AgentState.RUNNING;
				log("state is now ", agentState);
			}
			break;
		case AGENT_STOP:
			synchronized(eventQueue)
			{
				if(!eventQueue.isEmpty())
				{
					while(!eventQueue.isEmpty())
						log("ignoring event ", eventQueue.poll());
				}
				if(toFromTransient)
					agentState = AgentState.TRANSIENT;
				else
					agentState = AgentState.STOPPED;
				log("state is now ", agentState);
			}
			eventQueue = null;
			localLog.doExit();
			return true;
		default:
			// do nothing
		}
		return false;
	}
	
	/**
	 * Changes the agent state between {@link AgentState#STOPPED} and {@link AgentState#TRANSIENT}. If the agent is in
	 * any other state, an exception is thrown.
	 * 
	 * @return <code>true</code> if the agent is now (after the change) in the {@link AgentState#TRANSIENT} state.
	 *         <code>false</code> if it is now in {@link AgentState#STOPPED}.
	 * 
	 * @throws RuntimeException
	 *             if the agent is in any other state than the two above.
	 */
	protected boolean FSMToggleTransient() throws RuntimeException
	{
		switch(agentState)
		{
		case STOPPED:
			agentState = AgentState.TRANSIENT;
			break;
		case TRANSIENT:
			agentState = AgentState.STOPPED;
			break;
		default:
			throw new IllegalStateException("Unable to toggle TRANSIENT state while in " + agentState);
		}
		if(localLog.getUnitName() != null)
			// protect against locking the log
			log("state switched to ", agentState);
		return isTransient();
	}
	
	/**
	 * Adds a feature to the agent, which has been configured beforehand. The agent will register with the feature, as
	 * parent.
	 * <p>
	 * The feature will be identified by the agent by means of its {@link AgentShardCore#getShardDesignation()} method.
	 * Only one instance per designation (functionality) will be allowed.
	 * 
	 * @param feature
	 *            - the {@link AgentShardCore} instance to add.
	 * @return the agent instance itself. This can be used to continue adding other features.
	 */
	protected CompositeAgent addFeature(AgentShardCore feature)
	{
		if(!canAddFeatures())
			throw new IllegalStateException("Cannot add features in state [" + agentState + "].");
		if(feature == null)
			throw new InvalidParameterException("Feature is null");
		if(hasFeature(feature.getShardDesignation()))
			throw new InvalidParameterException(
					"Cannot add multiple features for designation [" + feature.getShardDesignation() + "]");
		features.put(feature.getShardDesignation(), feature);
		featureOrder.add(feature);
		feature.addContext(this.asContext());
		return this;
	}
	
	/**
	 * Removes an existing feature of the agent.
	 * 
	 * @param designation
	 *            - the designation of the feature to remove.
	 * @return a reference to the just-removed feature instance.
	 */
	protected AgentShardCore removeFeature(AgentShardDesignation designation)
	{
		if(!hasFeature(designation))
			throw new InvalidParameterException("Feature [" + designation + "] does not exist");
		AgentShardCore feature = getFeature(designation);
		featureOrder.remove(feature);
		features.remove(designation);
		return feature;
	}
	
	/**
	 * Returns <code>true</code> if the agent contains said feature.
	 * 
	 * @param designation
	 *            - the designation of the feature to search.
	 * @return <code>true</code> if the feature exists, <code>false</code> otherwise.
	 */
	protected boolean hasFeature(AgentShardDesignation designation)
	{
		return features.containsKey(designation);
	}
	
	/**
	 * Retrieves a feature of the agent, by designation.
	 * <p>
	 * It is <i>strongly recommended</i> that the reference is not kept, as the feature may be removed without notice.
	 * 
	 * @param designation
	 *            - the designation of the feature to retrieve.
	 * @return the {@link AgentShardCore} instance, if any. <code>null</code> otherwise.
	 */
	protected AgentShardCore getFeature(AgentShardDesignation designation)
	{
		return features.get(designation);
	}
	
	/**
	 * Retrieves the link to the support implementation.
	 * 
	 * @return the support implementation.
	 */
	protected Object getSupportImplementation()
	{
		return supportLink;
	}
	
	/**
	 * Returns the name of the agent. It is the name that has been set through the <code>AGENT_NAME</code> parameter.
	 * 
	 * @return the name of the agent.
	 */
	@Override
	public String getName()
	{
		return agentName;
	}
	
	/**
	 * Checks if the agent is currently in <code>RUNNING</code> state. In case features are added during this state,
	 * they must consider that the agent is already running and no additional {@link AgentEventType#AGENT_START} events
	 * will be issued.
	 * 
	 * @return <code>true</code> if the agent is currently <code>RUNNING</code>; <code>false</code> otherwise.
	 */
	@Override
	public boolean isRunning()
	{
		return agentState == AgentState.RUNNING;
	}
	
	/**
	 * Checks if the agent is currently in <code>STOPPED</code> state.
	 * 
	 * @return <code>true</code> if the agent is currently <code>STOPPED</code>; <code>false</code> otherwise.
	 */
	public boolean isStopped()
	{
		return agentState == AgentState.STOPPED;
	}
	
	/**
	 * Checks whether the agent is in the <code>TRANSIENT</code> state.
	 * 
	 * @return <code>true</code> if the agent is currently <code>TRANSIENT</code>; <code>false</code> otherwise.
	 */
	public boolean isTransient()
	{
		return agentState == AgentState.TRANSIENT;
	}
	
	/**
	 * Checks whether the agent is in the <code>STARTING</code> state.
	 * 
	 * @return <code>true</code> if the agent is currently <code>STARTING</code>; <code>false</code> otherwise.
	 */
	protected boolean isStarting()
	{
		return agentState == AgentState.STARTING;
	}
	
	/**
	 * Checks whether the agent is in the <code>STOPPING</code> state.
	 * 
	 * @return <code>true</code> if the agent is currently <code>STOPPING</code>; <code>false</code> otherwise.
	 */
	protected boolean isStopping()
	{
		return agentState == AgentState.STOPPING;
	}
	
	/**
	 * Checks if the state of the agent allows adding features. Features should not be added in intermediary states in
	 * which the agent is starting or stopping.
	 * 
	 * @return <code>true</code> if in the current state features can be added.
	 */
	public boolean canAddFeatures()
	{
		return (agentState == AgentState.STOPPED) || (agentState == AgentState.RUNNING);
	}
	
	/**
	 * Returns the name of the agent.
	 */
	@Override
	public String toString()
	{
		return getName();
	}
	
	/**
	 * Use this method to output to the local log. Do not abuse. The call is relayed to a
	 * {@link UnitComponent#li(String, Object...)} call.
	 * 
	 * @param message
	 *            - the message.
	 * @param arguments
	 *            - objects to include in the message.
	 */
	protected void log(String message, Object... arguments)
	{
		if(USE_LOCAL_LOG && (localLog != null))
		{
			if(localLog.getUnitName() == null)
			{
				if(getName() != null)
					localLog.setUnitName(getName() + "#");
				else
					localLog.setUnitName(super.toString().replace(getClass().getName(), "CompAg") + "#");
			}
			localLog.li(message, arguments);
		}
	}
}
