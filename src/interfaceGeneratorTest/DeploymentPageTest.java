package interfaceGeneratorTest;

import net.xqhs.flash.FlashBoot;

import java.util.ArrayList;
import java.util.Arrays;

public class DeploymentPageTest {
    public static void main(String[] args) {

        if (args.length == 0) {
            // YAML configuration provided in command line args
            System.err.println("No args provided");
            return;
        }

        String test_args = "";

        test_args += " -package interfaceGenerator -loader agent:composite";
        test_args += " -agent composite:AgentA";
        test_args += " -shard GuiShard";
        test_args += " -config";

        var args_list = new ArrayList<>(Arrays.asList(test_args.split(" ")));

        StringBuilder configuration = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            configuration.append(args[i]);
            if (i != args.length - 1) {
                configuration.append(" ");
            }
        }

        args_list.add(configuration.toString());

        FlashBoot.main(args_list.toArray(new String[0]));
    }
}
