package org.embeddedt.modernfix.mixin.perf.modern_resourcepacks;

import com.google.common.base.Joiner;
import com.mojang.datafixers.util.Pair;
import net.minecraft.server.packs.PackType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.resource.PathPackResources;
import org.embeddedt.modernfix.util.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The built-in resource caching provided by Forge is overengineered and doesn't work correctly
 * in many scenarios. This is a port of the well-tested implementation from ModernFix 1.16
 * and 1.18.
 */
@Mixin(PathPackResources.class)
public abstract class PathPackResourcesMixin {

    @Shadow protected abstract Path resolve(String... paths);

    @Shadow @Final private String packName;
    @Shadow @Final private Path source;

    @Shadow @NotNull protected abstract Set<String> getNamespacesFromDisk(PackType type);

    private EnumMap<PackType, Set<String>> namespacesByType;
    private EnumMap<PackType, HashMap<String, List<Pair<Path, String>>>> rootListingByNamespaceAndType;
    private boolean hasGeneratedListings;
    private Set<String> containedPaths;

    private FileSystem resourcePackFS;

    private static Joiner slashJoiner = Joiner.on('/');

    @Inject(method = "<init>", at = @At("TAIL"))
    private void cacheResources(String packName, Path source, CallbackInfo ci) {
        this.resourcePackFS = source.getFileSystem();
        this.namespacesByType = new EnumMap<>(PackType.class);
        this.hasGeneratedListings = false;
    }

    private void generateResourceCache() {
        synchronized (this) {
            if(hasGeneratedListings)
                return;
            EnumMap<PackType, HashMap<String, List<Pair<Path, String>>>> rootListingByNamespaceAndType = new EnumMap<>(PackType.class);
            HashSet<String> containedPaths = new HashSet<>();
            for(PackType type : PackType.values()) {
                Set<String> namespaces = this.getNamespacesFromDisk(type);
                HashMap<String, List<Pair<Path, String>>> rootListingForNamespaces = new HashMap<>();
                for(String namespace : namespaces) {
                    try {
                        Path root = this.resolve(type.getDirectory(), namespace).toAbsolutePath();
                        try (Stream<Path> stream = Files.walk(root)) {
                            ArrayList<Pair<Path, String>> rootListingPaths = new ArrayList<>();
                            stream
                                    .map(path -> root.relativize(path.toAbsolutePath()))
                                    .filter(this::isValidCachedResourcePath)
                                    .forEach(path -> {
                                        if(!path.toString().endsWith(".mcmeta"))
                                            rootListingPaths.add(Pair.of(path, slashJoiner.join(path)));
                                        String mergedPath = slashJoiner.join(type.getDirectory(), namespace, path);
                                        containedPaths.add(mergedPath);
                                    });
                            rootListingPaths.trimToSize();
                            rootListingForNamespaces.put(namespace, rootListingPaths);
                        }
                    } catch(IOException e) {
                        rootListingForNamespaces.put(namespace, Collections.emptyList());
                    }
                }
                rootListingByNamespaceAndType.put(type, rootListingForNamespaces);
            }
            this.rootListingByNamespaceAndType = rootListingByNamespaceAndType;
            this.containedPaths = containedPaths;
            this.hasGeneratedListings = true;
        }
    }

    private boolean isValidCachedResourcePath(Path path) {
        String str = path.toString();
        for(int i = 0; i < str.length(); i++) {
            if(!ResourceLocation.validPathChar(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Inject(method = "getNamespaces", at = @At("HEAD"), cancellable = true)
    private void useCacheForNamespaces(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        Set<String> cachedNamespaces;
        synchronized (this.namespacesByType) {
            cachedNamespaces = this.namespacesByType.get(type);
        }
        if(cachedNamespaces != null) {
            cir.setReturnValue(cachedNamespaces);
        }
    }

    @Inject(method = "getNamespaces", at = @At("TAIL"))
    private void storeCacheForNamespaces(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        synchronized (this.namespacesByType) {
            this.namespacesByType.put(type, cir.getReturnValue());
        }
    }

    @Inject(method = "hasResource(Ljava/lang/String;)Z", at = @At(value = "HEAD"), cancellable = true)
    private void useCacheForExistence(String path, CallbackInfoReturnable<Boolean> cir) {
        this.generateResourceCache();
        cir.setReturnValue(this.containedPaths.contains(FileUtil.normalize(path)));
    }

    /**
     * @author embeddedt
     * @reason Use cached listing of mod resources
     */
    @Inject(method = "getResources", at = @At("HEAD"), cancellable = true)
    public void getResources(PackType type, String resourceNamespace, String pathIn, Predicate<ResourceLocation> filter, CallbackInfoReturnable<Collection<ResourceLocation>> cir)
    {
        this.generateResourceCache();
        if(!pathIn.endsWith("/"))
            pathIn = pathIn + "/";
        final String testPath = pathIn;
        Collection<ResourceLocation> cachedListing = this.rootListingByNamespaceAndType.get(type).getOrDefault(resourceNamespace, Collections.emptyList()).stream().
                filter(path -> path.getSecond().startsWith(testPath)). // Make sure the target path is inside this one
                // Finally we need to form the RL, so use the first name as the domain, and the rest as the path
                // It is VERY IMPORTANT that we do not rely on Path.toString as this is inconsistent between operating systems
                // Join the path names ourselves to force forward slashes
                map(path -> new ResourceLocation(resourceNamespace, path.getSecond())).
                filter(filter::test). // Test the file name against the predicate
                collect(Collectors.toList());
        cir.setReturnValue(cachedListing);
    }
}