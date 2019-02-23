package me.desht.modularrouters.client.gui.filter;

import com.google.common.collect.Lists;
import me.desht.modularrouters.ModularRouters;
import me.desht.modularrouters.client.gui.BackButton;
import me.desht.modularrouters.container.ContainerSmartFilter;
import me.desht.modularrouters.item.smartfilter.ModFilter;
import me.desht.modularrouters.network.FilterSettingsMessage;
import me.desht.modularrouters.network.PacketHandler;
import me.desht.modularrouters.util.ModNameCache;
import me.desht.modularrouters.util.SlotTracker;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.List;

public class GuiModFilter extends GuiFilterContainer {
    private static final ResourceLocation textureLocation = new ResourceLocation(ModularRouters.MODID, "textures/gui/modfilter.png");

    private static final int GUI_WIDTH = 176;
    private static final int GUI_HEIGHT = 252;

    private static final int ADD_BUTTON_ID = 1;
    private static final int BACK_BUTTON_ID = 2;
    private static final int BASE_REMOVE_ID = 100;

    private final List<String> mods = Lists.newArrayList();

    private ItemStack prevInSlot = ItemStack.EMPTY;
    private String modId = "";
    private String modName = "";

    public GuiModFilter(ContainerSmartFilter container) {
        super(container);

        this.xSize = GUI_WIDTH;
        this.ySize = GUI_HEIGHT;

        mods.addAll(ModFilter.getModList(filterStack));
    }

    @Override
    public void initGui() {
        super.initGui();

//        buttonList.clear();
        if (SlotTracker.getInstance(mc.player).getFilterSlot() >= 0) {
            addButton(new BackButton(BACK_BUTTON_ID, guiLeft - 12, guiTop) {
                @Override
                public void onClick(double p_194829_1_, double p_194829_3_) {
                    closeGUI();
                }
            });
        }
        addButton(new Buttons.AddButton(ADD_BUTTON_ID, guiLeft + 154, guiTop + 19) {
            @Override
            public void onClick(double p_194829_1_, double p_194829_3_) {
                if (!modId.isEmpty()) {
                    NBTTagCompound ext = new NBTTagCompound();
                    ext.putString("ModId", modId);
                    if (router != null) {
                        PacketHandler.NETWORK.sendToServer(new FilterSettingsMessage(
                                FilterSettingsMessage.Operation.ADD_STRING, router.getPos(), ext));
                    } else {
                        PacketHandler.NETWORK.sendToServer(new FilterSettingsMessage(
                                FilterSettingsMessage.Operation.ADD_STRING, hand, ext));
                    }
                    inventorySlots.inventorySlots.get(0).putStack(ItemStack.EMPTY);
                }
            }
        });
        for (int i = 0; i < mods.size(); i++) {
            addButton(new Buttons.DeleteButton(BASE_REMOVE_ID + i, guiLeft + 8, guiTop + 44 + i * 19) {
                @Override
                public void onClick(double p_194829_1_, double p_194829_3_) {
                    if (id >= BASE_REMOVE_ID && id < BASE_REMOVE_ID + mods.size()) {
                        NBTTagCompound ext = new NBTTagCompound();
                        ext.putInt("Pos", id - BASE_REMOVE_ID);
                        if (router != null) {
                            PacketHandler.NETWORK.sendToServer(new FilterSettingsMessage(
                                    FilterSettingsMessage.Operation.REMOVE_AT, router.getPos(), ext));
                        } else {
                            PacketHandler.NETWORK.sendToServer(new FilterSettingsMessage(
                                    FilterSettingsMessage.Operation.REMOVE_AT, hand, ext));
                        }
                    }
                }
            });
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        String title = filterStack.getDisplayName() + (router != null ? I18n.format("guiText.label.installed") : "");
        fontRenderer.drawString(title, this.xSize / 2f - fontRenderer.getStringWidth(title) / 2f, 8, 0x404040);

        if (!modName.isEmpty()) {
            fontRenderer.drawString(modName, 29, 23, 0x404040);
        }

        for (int i = 0; i < mods.size(); i++) {
            String mod = ModNameCache.getModName(mods.get(i));
            fontRenderer.drawString(mod, 28, 47 + i * 19, 0x404080);
        }
    }

    @Override
    public void tick() {
        super.tick();

        ItemStack inSlot = inventorySlots.getInventory().get(0);
        if (inSlot.isEmpty() && !prevInSlot.isEmpty()) {
            modId = modName = "";
        } else if (!inSlot.isEmpty() && (prevInSlot.isEmpty() || !inSlot.isItemEqualIgnoreDurability(prevInSlot))) {
            modId = inSlot.getItem().getRegistryName().getNamespace();
            modName = ModNameCache.getModName(modId);
        }
        prevInSlot = inSlot;
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager().bindTexture(textureLocation);
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, this.xSize, this.ySize);
    }

    @Override
    public void resync(ItemStack filterStack) {
        mods.clear();
        mods.addAll(ModFilter.getModList(filterStack));
        initGui();
    }
}
