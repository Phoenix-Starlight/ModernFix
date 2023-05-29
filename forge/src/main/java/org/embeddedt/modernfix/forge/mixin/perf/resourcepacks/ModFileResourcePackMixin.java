package org.embeddedt.modernfix.forge.mixin.perf.resourcepacks;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraftforge.resource.PathPackResources;
import org.embeddedt.modernfix.resources.ICachingResourcePack;
import org.embeddedt.modernfix.resources.PackResourcesCacheEngine;
import org.embeddedt.modernfix.util.PackTypeHelper;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The built-in resource caching provided by Forge is overengineered and doesn't work correctly
 * in many scenarios. This is a port of the well-tested implementation from ModernFix 1.16
 * and 1.18.
 */
@Mixin(PathPackResources.class)
public abstract class ModFileResourcePackMixin implements ICachingResourcePack {
    @Shadow protected abstract Path resolve(String... paths);

    @Shadow @NotNull
    protected abstract Set<String> getNamespacesFromDisk(PackType type);

    private PackResourcesCacheEngine cacheEngine;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void cacheResources(String packName, Path source, CallbackInfo ci) {
        invalidateCache();
        PackResourcesCacheEngine.track(this);
    }

    private PackResourcesCacheEngine generateResourceCache() {
        synchronized (this) {
            PackResourcesCacheEngine engine = this.cacheEngine;
            if(engine != null)
                return engine;
            this.cacheEngine = engine = new PackResourcesCacheEngine(this::getNamespacesFromDisk, (type, namespace) -> this.resolve(type.getDirectory(), namespace));
            return engine;
        }
    }

    @Override
    public void invalidateCache() {
        this.cacheEngine = null;
    }

    @Inject(method = "getNamespaces", at = @At("HEAD"), cancellable = true)
    private void useCacheForNamespaces(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        PackResourcesCacheEngine engine = cacheEngine;
        if(engine != null) {
            Set<String> namespaces = engine.getNamespaces(type);
            if(namespaces != null)
                cir.setReturnValue(namespaces);
        }
    }

    @Inject(method = "hasResource(Ljava/lang/String;)Z", at = @At(value = "HEAD"), cancellable = true)
    private void useCacheForExistence(String path, CallbackInfoReturnable<Boolean> cir) {
        PackResourcesCacheEngine engine = this.generateResourceCache();
        if(engine != null)
            cir.setReturnValue(engine.hasResource(path));
    }

    /**
     * @author embeddedt
     * @reason Use cached listing of mod resources
     */
    @Inject(method = "getResources", at = @At("HEAD"), cancellable = true)
    private void fastGetResources(PackType type, String resourceNamespace, String pathIn, Predicate<ResourceLocation> filter, CallbackInfoReturnable<Collection<ResourceLocation>> cir)
    {
        if(!PackTypeHelper.isVanillaPackType(type))
            return;
        cir.setReturnValue(this.generateResourceCache().getResources(type, resourceNamespace, pathIn, Integer.MAX_VALUE, filter));
    }
}
