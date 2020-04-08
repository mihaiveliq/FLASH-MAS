package monitoringAndControl;

import monitoringAndControl.gui.GUIBoard;
import net.xqhs.flash.core.Entity;
import net.xqhs.flash.core.agent.AgentEvent;
import net.xqhs.flash.core.agent.AgentWave;
import net.xqhs.flash.core.shard.AgentShard;
import net.xqhs.flash.core.shard.AgentShardDesignation;
import net.xqhs.flash.core.shard.ShardContainer;
import net.xqhs.flash.core.support.MessagingPylonProxy;
import net.xqhs.flash.core.support.MessagingShard;
import net.xqhs.flash.core.support.Pylon;
import net.xqhs.flash.local.LocalSupport;

import javax.swing.*;

public class CentralMonitoringAndControlEntity implements  Entity<Pylon> {

    protected static final String	SHARD_ENDPOINT				        = "control";

    protected static final String	OTHER_SHARD_ENDPOINT				= "control";

    private MessagingShard          centralMessagingShard;

    private String                  name                                 = null;

    /*
    * Graphic User Interface
    * */
    private GUIBoard                GUI;

    private static boolean          RUNNING_STATE;


    // Proxy used to receive messages from outer entities; e.g. logs from agents
    public ShardContainer          proxy = new ShardContainer() {
        @Override
        public void postAgentEvent(AgentEvent event) {
            System.out.println(event.toString());
        }

        @Override
        public AgentShard getAgentShard(AgentShardDesignation designation) {
            return null;
        }

        @Override
        public String getEntityName() {
            return getName();
        }
    };

    public CentralMonitoringAndControlEntity(String name) {
        this.name = name;
        centralMessagingShard = new LocalSupport.SimpleLocalMessaging();
        centralMessagingShard.addContext(proxy);
    }


    @Override
    public boolean start() {
        System.out.println(getName() + "## CENTRAL MONITORING ENTITY STARTED...");
        RUNNING_STATE = true;
        startGUIBoard();
        return true;
    }

    public void startGUIBoard() {
        SwingUtilities.invokeLater(() -> {
            try {
                GUI = new GUIBoard(this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public boolean stop() {
        System.out.println(getName() + "## CENTRAL MONITORING ENTITY STOPPED...");
        RUNNING_STATE = false;
        return true;
    }

    @Override
    public boolean isRunning() {
        return RUNNING_STATE;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean addContext(EntityProxy<Pylon> context) {
        return centralMessagingShard.addGeneralContext(context);
    }

    @Override
    public boolean removeContext(EntityProxy<Pylon> context) {
        return false;
    }

    @Override
    public boolean addGeneralContext(EntityProxy<? extends Entity<?>> context) {
        return addContext((MessagingPylonProxy) context);
    }

    @Override
    public boolean removeGeneralContext(EntityProxy<? extends Entity<?>> context) {
        return false;
    }

    @Override
    public <C extends Entity<Pylon>> EntityProxy<C> asContext() {
        return null;
    }


    /**
    * Requests to send a control command. This is mainly coming
     * from the GUI component.
    **/

    public boolean sendGUICommand(String entityName, String command) {
        centralMessagingShard
                .sendMessage(
                        AgentWave.makePath(getName(), SHARD_ENDPOINT),
                        AgentWave.makePath(entityName, OTHER_SHARD_ENDPOINT),
                        command);
        return true;
    }
}
