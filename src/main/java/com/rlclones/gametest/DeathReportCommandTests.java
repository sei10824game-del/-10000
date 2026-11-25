package com.rlclones.gametest;
import com.rlclones.ai.CloneController;
import net.minecraft.gametest.framework.*;
import net.minecraftforge.gametest.*;
import java.util.*;

@GameTestHolder("rlclones_reports")
@PrefixGameTestTemplate(false)
public final class DeathReportCommandTests {
    @GameTest(template="arena",batch="death-reports",timeoutTicks=40)
    public static void realCommandsPageAndGroupThreeHundredCombinations(GameTestHelper h){
        var before=new HashMap<>(CloneController.DEATHS);
        try{
            CloneController.DEATHS.clear();for(int i=0;i<300;i++)CloneController.DEATHS.put("infected_damage2 by mod:mob"+i+" during STAIRS",1);
            CloneController.DEATHS.put("fall during SHAFT",50);var expected=new HashMap<>(CloneController.DEATHS);
            var server=h.getLevel().getServer();var source=server.createCommandSourceStack().withSuppressedOutput();
            h.assertTrue(server.getCommands().performPrefixedCommand(source,"rlclone deaths")==2,"default command groups causes instead of flooding chat");
            h.assertTrue(server.getCommands().performPrefixedCommand(source,"rlclone deaths details 1")==8,"first detail page has only eight rows");
            h.assertTrue(server.getCommands().performPrefixedCommand(source,"rlclone deaths details 38")==5,"last page retains the remaining combinations");
            h.assertTrue(server.getCommands().performPrefixedCommand(source,"rlclone deaths 38")==5,"numeric shorthand reaches the same detail page");
            h.assertTrue(server.getCommands().performPrefixedCommand(source,"rlclone deaths mobs")==8,"mob aggregation is paged");
            h.assertTrue(server.getCommands().performPrefixedCommand(source,"rlclone deaths actions")==2,"actions aggregate independently");
            h.assertTrue(expected.equals(CloneController.DEATHS),"queries do not erase old counts");
        }finally{CloneController.DEATHS.clear();CloneController.DEATHS.putAll(before);}h.succeed();
    }
}
