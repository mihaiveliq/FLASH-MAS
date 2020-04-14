package test.simplePingPong;

import java.lang.reflect.InvocationTargetException;
import java.util.Timer;
import java.util.TimerTask;

import net.xqhs.flash.core.DeploymentConfiguration;
import net.xqhs.flash.core.Entity;
import net.xqhs.flash.core.agent.Agent;
import net.xqhs.flash.core.agent.AgentEvent;
import net.xqhs.flash.core.agent.AgentEvent.AgentEventType;
import net.xqhs.flash.core.agent.AgentWave;
import net.xqhs.flash.core.shard.AgentShard;
import net.xqhs.flash.core.shard.AgentShardDesignation;
import net.xqhs.flash.core.shard.AgentShardDesignation.StandardAgentShard;
import net.xqhs.flash.core.shard.ShardContainer;
import net.xqhs.flash.core.support.MessagingPylonProxy;
import net.xqhs.flash.core.support.MessagingShard;
import net.xqhs.flash.core.support.Pylon;
import net.xqhs.flash.core.support.PylonProxy;
import net.xqhs.flash.core.util.MultiTreeMap;
import net.xqhs.flash.core.util.PlatformUtils;

/**
 * The implementation of the agents.
 */
public class AgentPingPong implements Agent
{
	/**
	 * The name of the component parameter that contains the id of the other agent.
	 */
	protected static final String	OTHER_AGENT_PARAMETER_NAME	= "otherAgent";
	/**
	 * Endpoint element for this shard.
	 */
	protected static final String	SHARD_ENDPOINT				= "ping";
	/**
	 * Initial delay before the first ping message.
	 */
	protected static final long		PING_INITIAL_DELAY			= 5000;
	/**
	 * Time between ping messages.
	 */
	protected static final long		PING_PERIOD					= 2000;
	
	/**
	 * Timer for pinging.
	 */
	Timer							pingTimer					= null;
	/**
	 * Cache for the name of the other agent.
	 */
	String							otherAgent					= null;
	/**
	 * The messaging shard.
	 */
	MessagingShard					msgShard					= null;
	/**
	 * The name of this agent.
	 */
	String							agentName					= null;
	
	/**
	 * @param configuration
	 */
	public AgentPingPong(MultiTreeMap configuration)
	{
		if(configuration.isSet(OTHER_AGENT_PARAMETER_NAME))
			otherAgent = configuration.getFirstValue(OTHER_AGENT_PARAMETER_NAME);
		agentName = configuration.getFirstValue(DeploymentConfiguration.NAME_ATTRIBUTE_NAME);
	}
	
	@Override
	public boolean start()
	{
		if(msgShard == null)
			throw new IllegalStateException("No messaging shard present");
		msgShard.signalAgentEvent(new AgentEvent(AgentEventType.AGENT_START));
		if(otherAgent != null)
		{
			pingTimer = new Timer();
			pingTimer.schedule(new TimerTask() {
				/**
				 * The index of the message sent.
				 */
				int tick = 0;
				
				@Override
				public void run()
				{
					tick++;
					System.out.println("Sending the message....");
					msgShard.sendMessage(AgentWave.makePath(getName(), "ping"), AgentWave.makePath(otherAgent, "pong"),
							"ping-no " + tick);
				}
			}, PING_INITIAL_DELAY, PING_PERIOD);
		}
		return true;
	}
	
	@Override
	public boolean stop()
	{
		return false;
	}
	
	@Override
	public boolean isRunning()
	{
		return true;
	}
	
	@Override
	public String getName()
	{
		return agentName;
	}
	
	@Override
	public boolean addContext(EntityProxy<Pylon> context)
	{
		PylonProxy proxy = (PylonProxy) context;
		String recommendedShard = proxy
				.getRecommendedShardImplementation(AgentShardDesignation.standardShard(StandardAgentShard.MESSAGING));
		try
		{
			msgShard = (MessagingShard) PlatformUtils.getClassFactory().loadClassInstance(recommendedShard, null, true);
		} catch(ClassNotFoundException | InstantiationException | NoSuchMethodException | IllegalAccessException
				| InvocationTargetException e)
		{
			e.printStackTrace();
		}
		msgShard.addContext(new ShardContainer() {
			@Override
			public String getEntityName()
			{
				return getName();
			}
			
			@Override
			public void postAgentEvent(AgentEvent event)
			{
				System.out.println(event.toString());
				if(event.getType().equals(AgentEventType.AGENT_WAVE) && otherAgent == null)
				{
					String replyContent = ((AgentWave) event).getContent() + " reply";
					msgShard.sendMessage(AgentWave.makePath(getName(), SHARD_ENDPOINT),
							((AgentWave) event).getCompleteSource(), replyContent);
				}
			}
			
			@Override
			public AgentShard getAgentShard(AgentShardDesignation designation)
			{
				return null;
			}
		});
		return msgShard.addGeneralContext(context);
	}
	
	@Override
	public boolean removeContext(EntityProxy<Pylon> context)
	{
		return false;
	}
	
	@Override
	public boolean addGeneralContext(EntityProxy<? extends Entity<?>> context)
	{
		return addContext((MessagingPylonProxy) context);
	}
	
	@Override
	public boolean removeGeneralContext(EntityProxy<? extends Entity<?>> context)
	{
		return false;
	}
	
	@Override
	public <C extends Entity<Pylon>> EntityProxy<C> asContext()
	{
		return null;
	}
	
}