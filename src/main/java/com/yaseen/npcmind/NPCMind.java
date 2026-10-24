package com.yaseen.npcmind;

import com.mojang.logging.LogUtils;
import com.yaseen.npcmind.entity.ModEntities;
import com.yaseen.npcmind.entity.CompanionAttributes;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import org.slf4j.Logger;

@Mod(NPCMind.MOD_ID)
public final class NPCMind {
    public static final String MOD_ID = "npcmind";
    public static final Logger LOG = LogUtils.getLogger();

    public NPCMind() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModEntities.ENTITY_TYPES.register(modBus);
        modBus.addListener((EntityAttributeCreationEvent event) ->
                event.put(ModEntities.COMPANION.get(), CompanionAttributes.create().build()));
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Host mods control NPC spawning and player interaction; this library never auto-spawns.
        LOG.info("NPC Mind library initialized.");
    }
}
