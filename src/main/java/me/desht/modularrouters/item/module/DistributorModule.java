package me.desht.modularrouters.item.module;

import me.desht.modularrouters.block.tile.TileEntityItemRouter;
import me.desht.modularrouters.client.util.ClientUtil;
import me.desht.modularrouters.client.util.TintColor;
import me.desht.modularrouters.container.ContainerModule;
import me.desht.modularrouters.core.ModContainerTypes;
import me.desht.modularrouters.logic.ModuleTarget;
import me.desht.modularrouters.logic.compiled.CompiledDistributorModule;
import me.desht.modularrouters.logic.compiled.CompiledModule;
import net.minecraft.inventory.container.ContainerType;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class DistributorModule extends SenderModule2 {
    @Override
    public void addSettingsInformation(ItemStack itemstack, List<ITextComponent> list) {
        super.addSettingsInformation(itemstack, list);

        CompiledDistributorModule cdm = new CompiledDistributorModule(null, itemstack);
        list.add(ClientUtil.xlate("guiText.tooltip.distributor.strategy").appendString(": ").mergeStyle(TextFormatting.YELLOW)
                .append(ClientUtil.xlate(cdm.getDistributionStrategy().getTranslationKey())).mergeStyle(TextFormatting.AQUA));
    }

    @Override
    public ContainerType<? extends ContainerModule> getContainerType() {
        return ModContainerTypes.CONTAINER_MODULE_DISTRIBUTOR.get();
    }

    @Override
    public CompiledModule compile(TileEntityItemRouter router, ItemStack stack) {
        return new CompiledDistributorModule(router, stack);
    }

    @Override
    public List<ModuleTarget> getStoredPositions(@Nonnull ItemStack stack) {
        Set<ModuleTarget> targets = TargetedModule.getTargets(stack, false);
        return new ArrayList<>(targets);
    }

    @Override
    public TintColor getItemTint() {
        return new TintColor(240, 240, 60);
    }

    @Override
    protected int getMaxTargets() {
        return 8;
    }

    @Override
    public int getRenderColor(int index) {
        return 0x80B0FF90;
    }
}
