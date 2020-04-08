package interfaceGenerator.pylon;

import interfaceGenerator.Element;
import net.xqhs.flash.core.Entity;
import net.xqhs.flash.core.node.Node;
import net.xqhs.flash.core.shard.AgentShardDesignation;
import net.xqhs.flash.core.support.Pylon;
import net.xqhs.flash.core.util.MultiTreeMap;
import net.xqhs.util.config.Config;
import net.xqhs.util.config.Configurable;

import java.util.Set;

public class AndroidUiPylon implements Pylon {
    private final static String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>";
    private static int indentLevel = 0;

    public static String generate(Element element) {
        // TODO: Android interface generator
        return null;
    }

    @Override
    public Set<String> getSupportedServices() {
        return null;
    }

    @Override
    public String getRecommendedShardImplementation(AgentShardDesignation shardType) {
        return null;
    }

    @Override
    public boolean configure(MultiTreeMap configuration) {
        return false;
    }

    @Override
    public boolean start() {
        return true;
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
    public boolean addContext(EntityProxy<Node> context) {
        return false;
    }

    @Override
    public boolean removeContext(EntityProxy<Node> context) {
        return false;
    }

    @Override
    public boolean addGeneralContext(EntityProxy<? extends Entity<?>> context) {
        return false;
    }

    @Override
    public boolean removeGeneralContext(EntityProxy<? extends Entity<?>> context) {
        return false;
    }

    @Override
    public <C extends Entity<Node>> EntityProxy<C> asContext() {
        return null;
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

