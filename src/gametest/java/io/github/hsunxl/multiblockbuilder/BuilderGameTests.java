package io.github.hsunxl.multiblockbuilder;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;
import java.util.function.Consumer;

@GameTestHolder(BuilderMod.ID)
@PrefixGameTestTemplate(false)
public final class BuilderGameTests {
    private static Blueprint blueprint() {
        return Blueprint.parse(ResourceLocation.parse("multiblock_builder:test"),JsonParser.parseString("""
            {"size":[2,1,1],"survival":true,"blocks":[
            {"pos":[0,0,0],"block":"minecraft:stone"},
            {"pos":[1,0,0],"block":"minecraft:stone"}]}
            """).getAsJsonObject());
    }
    private static ServerPlayer player(GameTestHelper h) {
        var p=h.makeMockServerPlayerInLevel();
        var at=h.absolutePos(new BlockPos(1,2,1)); p.setPos(at.getX(),at.getY(),at.getZ());
        p.getAbilities().instabuild=false; p.getAbilities().mayBuild=true;
        p.getInventory().clearContent(); p.getInventory().setItem(0,new ItemStack(BuilderMod.TOOL.get()));
        return p;
    }
    private static BlockPos anchor(GameTestHelper h) {
        h.setBlock(1,2,1,Blocks.AIR); h.setBlock(2,2,1,Blocks.AIR);
        return h.absolutePos(new BlockPos(1,2,1));
    }
    @GameTest(template="empty") public static void survivalConsumesExactlyOnce(GameTestHelper h) {
        var p=player(h); var anchor=anchor(h); p.getInventory().setItem(1,new ItemStack(Items.STONE,4));
        h.assertTrue(Placement.build(p,p.getMainHandItem(),blueprint(),anchor,0),"Build should succeed");
        h.assertTrue(p.getInventory().getItem(1).getCount()==2,"Exactly 2 material items charged");
        h.assertTrue(Placement.build(p,p.getMainHandItem(),blueprint(),anchor,0),"Existing correct blocks should be skipped");
        h.assertTrue(p.getInventory().getItem(1).getCount()==2,"No double charge");
        h.assertBlockPresent(Blocks.STONE,1,2,1); h.assertBlockPresent(Blocks.STONE,2,2,1); h.succeed();
    }
    @GameTest(template="empty") public static void missingMaterialsDoNotPlace(GameTestHelper h) {
        var p=player(h); var anchor=anchor(h); p.getInventory().setItem(1,new ItemStack(Items.STONE,1));
        h.assertTrue(!Placement.build(p,p.getMainHandItem(),blueprint(),anchor,0),"Missing materials must fail");
        h.assertTrue(p.getInventory().getItem(1).getCount()==1,"No charge on failure");
        h.assertBlockPresent(Blocks.AIR,1,2,1); h.assertBlockPresent(Blocks.AIR,2,2,1); h.succeed();
    }
    @GameTest(template="empty") public static void obstructionIsPreserved(GameTestHelper h) {
        var p=player(h); var anchor=anchor(h); p.getInventory().setItem(1,new ItemStack(Items.STONE,2));
        h.setBlock(2,2,1,Blocks.DIAMOND_BLOCK);
        h.assertTrue(!Placement.build(p,p.getMainHandItem(),blueprint(),anchor,0),"Blocked target must fail");
        h.assertTrue(p.getInventory().getItem(1).getCount()==2,"No charge");
        h.assertBlockPresent(Blocks.AIR,1,2,1); h.assertBlockPresent(Blocks.DIAMOND_BLOCK,2,2,1); h.succeed();
    }
    @GameTest(template="empty") public static void protectionRollsBack(GameTestHelper h) {
        var p=player(h); var anchor=anchor(h); p.getInventory().setItem(1,new ItemStack(Items.STONE,2));
        Consumer<BlockEvent.EntityPlaceEvent> cancel=e->{if(e.getEntity()==p) e.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(cancel);
        try {
            h.assertTrue(!Placement.build(p,p.getMainHandItem(),blueprint(),anchor,0),"Protection cancellation must fail");
            h.assertTrue(p.getInventory().getItem(1).getCount()==2,"No charge after rollback");
            h.assertBlockPresent(Blocks.AIR,1,2,1); h.assertBlockPresent(Blocks.AIR,2,2,1);
        } finally { NeoForge.EVENT_BUS.unregister(cancel); }
        h.succeed();
    }
    @GameTest(template="empty") public static void invalidBlueprintRejected(GameTestHelper h) {
        h.assertTrue(Blueprints.INSTANCE.get("")==null,"Empty tool selection must be safe");
        h.assertTrue(Blueprints.INSTANCE.get("multiblock_builder:demo_frame")!=null,"Data pack reload must discover bundled blueprint");
        boolean rejected=false;
        try { Blueprint.parse(ResourceLocation.parse("test:bad"),JsonParser.parseString("""
            {"size":[1,1,1],"survival":true,"blocks":[{"pos":[0,0,0],"block":"minecraft:oak_door"}]}
            """).getAsJsonObject()); } catch(IllegalArgumentException ex) { rejected=true; }
        h.assertTrue(rejected,"Multi-cell item mapping must require review/creative"); h.succeed();
    }
}
