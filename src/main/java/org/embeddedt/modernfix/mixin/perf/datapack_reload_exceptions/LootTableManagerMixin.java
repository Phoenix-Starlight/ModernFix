package org.embeddedt.modernfix.mixin.perf.datapack_reload_exceptions;

import net.minecraft.world.level.storage.loot.LootTables;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LootTables.class)
public class LootTableManagerMixin {
    @Redirect(method = "*(Lnet/minecraft/server/packs/resources/ResourceManager;Lcom/google/common/collect/ImmutableMap$Builder;Lnet/minecraft/resources/ResourceLocation;Lcom/google/gson/JsonElement;)V",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", remap = false))
    private void logWithoutStacktrace(Logger instance, String s, Object location, Object exc) {
        instance.error(s + ": {}", location, exc.toString());
    }
}
