package io.github.hsunxl.multiblockbuilder;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.*;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.slf4j.Logger;

@Mod(BuilderMod.ID)
public final class BuilderMod {
    public static final String ID="multiblock_builder";
    public static final Logger LOG=LogUtils.getLogger();
    public static final DeferredRegister.Items ITEMS=DeferredRegister.createItems(ID);
    public static final DeferredItem<BuilderItem> TOOL=ITEMS.register("builder",()->new BuilderItem(new Item.Properties().stacksTo(1)));
    public BuilderMod(IEventBus bus) {
        ITEMS.register(bus);
        bus.addListener(Network::register);
        bus.addListener((BuildCreativeModeTabContentsEvent e)-> { if(e.getTabKey()==CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(TOOL); });
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent e)->e.addListener(Blueprints.INSTANCE));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e)->BuilderCommands.register(e.getDispatcher()));
    }
}
