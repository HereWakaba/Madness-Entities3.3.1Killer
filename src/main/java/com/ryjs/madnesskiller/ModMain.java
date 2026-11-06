package com.ryjs.madnesskiller;

import com.ryjs.madnesskiller.command.KillerCommand;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;

import java.util.function.Consumer;

@Mod(ModMain.MODID)
public class ModMain {

    public static final String MODID = "madnesskiller";

    public ModMain() {
        IEventBus forge = MinecraftForge.EVENT_BUS;
        forge.addListener((Consumer<RegisterCommandsEvent>) event -> KillerCommand.register(event.getDispatcher()));

        forge.addListener((Consumer<TickEvent.ServerTickEvent>) KillerCommand::onServerTick);

        forge.addListener((Consumer<EntityJoinLevelEvent>) KillerCommand::onEntityJoin);

        forge.addListener(EventPriority.LOWEST, (Consumer<CommandEvent>) KillerCommand::onCommand);
    }

}
