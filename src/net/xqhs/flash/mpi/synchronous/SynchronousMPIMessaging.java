package net.xqhs.flash.mpi.synchronous;

import mpi.MPI;
import mpi.MPIException;
import mpi.Status;
import net.xqhs.flash.core.Entity;
import net.xqhs.flash.core.agent.Agent;
import net.xqhs.flash.core.agent.AgentEvent;
import net.xqhs.flash.core.agent.AgentWave;
import net.xqhs.flash.core.shard.AgentShardDesignation;
import net.xqhs.flash.core.support.MessagingPylonProxy;
import net.xqhs.flash.core.util.MultiTreeMap;
import net.xqhs.util.config.Config;
import net.xqhs.util.config.Configurable;
import static stefania.TreasureHunt.util.Constants.MPITagValue;

public class SynchronousMPIMessaging implements SynchronousMessagingShard {

    private static final long	serialVersionUID	= 1L;
    private MessagingPylonProxy pylon;

    public SynchronousMPIMessaging() {
        super();
    }

    @Override
    public boolean start() {
        return false;
    }

    @Override
    public boolean stop() {
        return false;
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public boolean addContext(EntityProxy<Agent> context) {
        return false;
    }

    @Override
    public boolean removeContext(EntityProxy<Agent> context) {
        return false;
    }

    @Override
    public boolean addGeneralContext(EntityProxy<? extends Entity<?>> context)
    {
        if(!(context instanceof MessagingPylonProxy))
            throw new IllegalStateException("Pylon Context is not of expected type.");
        pylon = (MessagingPylonProxy) context;
        return true;
    }

    @Override
    public boolean removeGeneralContext(EntityProxy<? extends Entity<?>> context) {
        return false;
    }

    @Override
    public <C extends Entity<Agent>> EntityProxy<C> asContext() {
        return null;
    }

    @Override
    public boolean sendMessage(String source, String destination, String content) {
        if(pylon == null) { // FIXME: use logging
            System.out.println("No pylon added as context.");
            return false;
        }

        pylon.send(source, destination, content);
        return true;
    }

    @Override
    public String getAgentAddress() {
        return null;
    }

    @Override
    public AgentWave blockingReceive(String source, String destination) {
        try {
            Status status = MPI.COMM_WORLD.probe(Integer.parseInt(source), MPITagValue);
            int length = status.getCount(MPI.BYTE);
            byte[] rawMessage = new byte[length];
            MPI.COMM_WORLD.recv(rawMessage, length, MPI.CHAR, Integer.parseInt(source), MPITagValue);
            AgentWave wave = new AgentWave(new String(rawMessage));
            return wave;
        } catch (MPIException e) {
            e.printStackTrace();
        }

        return null;
    }

    @Override
    public AgentShardDesignation getShardDesignation() {
        return null;
    }

    @Override
    public void signalAgentEvent(AgentEvent event) {

    }

    @Override
    public boolean configure(MultiTreeMap configuration) {
        return false;
    }

    @Override
    public Configurable makeDefaults() {
        return null;
    }

    @Override
    public Config lock() {
        return null;
    }

    @Override
    public Config build() {
        return null;
    }

    @Override
    public void ensureLocked() {

    }

    @Override
    public void locked() throws Config.ConfigLockedException {

    }
}
