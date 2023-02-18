package org.embeddedt.modernfix.mixin.perf.blast_search_trees;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.common.ingredients.IngredientFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(IngredientFilter.class)
public interface IngredientFilterInvoker {
    @Invoker(remap = false)
    List<ITypedIngredient<?>> invokeGetIngredientListUncached(String filterText);
}
