package me.desht.modularrouters.item.module;

import me.desht.modularrouters.block.tile.TileEntityItemRouter;
import me.desht.modularrouters.client.util.ClientUtil;
import me.desht.modularrouters.client.util.TintColor;
import me.desht.modularrouters.config.MRConfig;
import me.desht.modularrouters.container.ContainerModule;
import me.desht.modularrouters.core.ModContainerTypes;
import me.desht.modularrouters.core.ModItems;
import me.desht.modularrouters.integration.XPCollection;
import me.desht.modularrouters.logic.compiled.CompiledModule;
import me.desht.modularrouters.logic.compiled.CompiledVacuumModule;
import me.desht.modularrouters.util.MiscUtil;
import me.desht.modularrouters.util.ModNameCache;
import net.minecraft.inventory.container.ContainerType;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;

import java.util.List;

public class VacuumModule extends ItemModule implements IRangedModule {
    public VacuumModule() {
        super(ModItems.defaultProps());
    }

    @Override
    public ContainerType<? extends ContainerModule> getContainerType() {
        return ModContainerTypes.CONTAINER_MODULE_VACUUM.get();
    }

    @Override
    public CompiledModule compile(TileEntityItemRouter router, ItemStack stack) {
        return new CompiledVacuumModule(router, stack);
    }

    @Override
    public void addSettingsInformation(ItemStack itemstack, List<ITextComponent> list) {
        super.addSettingsInformation(itemstack, list);

        CompiledVacuumModule cvm = new CompiledVacuumModule(null, itemstack);
        if (cvm.isXpMode()) {
            XPCollection.XPCollectionType type = cvm.getXPCollectionType();
            ITextComponent modName = new StringTextComponent(ModNameCache.getModName(type.getModId())).mergeStyle(TextFormatting.BLUE);
            ITextComponent title = type.getDisplayName().copyRaw().mergeStyle(TextFormatting.AQUA);
            list.add(ClientUtil.xlate("guiText.label.xpVacuum")
                    .appendString(": ").mergeStyle(TextFormatting.YELLOW)
                    .append(title).appendString(" - ").append(modName));
            if (cvm.isAutoEjecting() && !type.isSolid()) {
                list.add(MiscUtil.settingsStr(TextFormatting.GREEN.toString(), ClientUtil.xlate("guiText.tooltip.xpVacuum.ejectFluid")));
            }
        }
    }

    @Override
    public int getBaseRange() {
        return MRConfig.Common.Module.vacuumBaseRange;
    }

    @Override
    public int getHardMaxRange() {
        return MRConfig.Common.Module.vacuumMaxRange;
    }

    @Override
    public boolean isOmniDirectional() {
        return true;
    }

    @Override
    public TintColor getItemTint() {
        return new TintColor(120, 48, 191);
    }
}
