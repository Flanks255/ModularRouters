package me.desht.modularrouters.block.tile;

import com.google.common.collect.Sets;
import me.desht.modularrouters.ModularRouters;
import me.desht.modularrouters.block.BlockItemRouter;
import me.desht.modularrouters.config.ConfigHandler;
import me.desht.modularrouters.container.ContainerItemRouter;
import me.desht.modularrouters.container.handler.BufferHandler;
import me.desht.modularrouters.core.ObjectRegistry;
import me.desht.modularrouters.event.TickEventHandler;
import me.desht.modularrouters.item.module.DetectorModule.SignalType;
import me.desht.modularrouters.item.module.FluidModule;
import me.desht.modularrouters.item.module.ItemModule;
import me.desht.modularrouters.item.module.ItemModule.RelativeDirection;
import me.desht.modularrouters.item.upgrade.CamouflageUpgrade;
import me.desht.modularrouters.item.upgrade.ItemUpgrade;
import me.desht.modularrouters.logic.RouterRedstoneBehaviour;
import me.desht.modularrouters.logic.compiled.CompiledExtruderModule;
import me.desht.modularrouters.logic.compiled.CompiledModule;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.IBlockReader;
import net.minecraft.world.IInteractionObject;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

public class TileEntityItemRouter extends TileEntity implements ITickable, ICamouflageable, IInteractionObject {
    private static final int N_MODULE_SLOTS = 9;
    private static final int N_UPGRADE_SLOTS = 5;
    private static final int N_BUFFER_SLOTS = 1;

    public static final int COMPILE_MODULES = 0x01;
    public static final int COMPILE_UPGRADES = 0x02;

    private static final String NBT_ACTIVE = "Active";
    private static final String NBT_ACTIVE_TIMER = "ActiveTimer";
    private static final String NBT_ECO_MODE = "EcoMode";
    private static final String NBT_SIDES = "Sides";
    private static final String NBT_PERMITTED = "Permitted";
    private static final String NBT_BUFFER = "Buffer";
    private static final String NBT_MODULES = "Modules";
    private static final String NBT_UPGRADES = "Upgrades";
    private static final String NBT_EXTRA = "Extra";
    private static final String NBT_REDSTONE_MODE = "Redstone";
    private static final String NBT_TICK_RATE = "TickRate";
    private static final String NBT_FLUID_TRANSFER_RATE = "FluidTransfer";
    private static final String NBT_ITEMS_PER_TICK = "ItemsPerTick";
    private static final String NBT_MUFFLERS = "Mufflers";

    private int counter = 0;
    private int pulseCounter = 0;

    private RouterRedstoneBehaviour redstoneBehaviour = RouterRedstoneBehaviour.ALWAYS;

    private final BufferHandler bufferHandler = new BufferHandler(this);
    private final LazyOptional<IItemHandler> inventoryCap = LazyOptional.of(() -> bufferHandler);

    private final ItemStackHandler modulesHandler = new RouterItemHandler.ModuleHandler(this);
    private final ItemStackHandler upgradesHandler = new RouterItemHandler.UpgradeHandler(this);

    private final List<CompiledModule> compiledModules = new ArrayList<>();
    private byte recompileNeeded = COMPILE_MODULES | COMPILE_UPGRADES;
    private int tickRate = ConfigHandler.ROUTER.baseTickRate.get();
    private int itemsPerTick = 1;
//    private final int[] upgradeCount = new int[UpgradeType.values().length];
    private final Map<ResourceLocation, Integer> upgradeCount = new HashMap<>();
    private int totalUpgradeCount;
    private int moduleCount;

    private int fluidTransferRate;  // mB/t
    private int fluidTransferRemainingIn = 0;
    private int fluidTransferRemainingOut = 0;

    // for tracking redstone emission levels for the detector module
    private final int SIDES = EnumFacing.values().length;
    private final int[] redstoneLevels = new int[SIDES];
    private final int[] newRedstoneLevels = new int[SIDES];
    private final SignalType[] signalType = new SignalType[SIDES];
    private final SignalType[] newSignalType = new SignalType[SIDES];
    private boolean canEmit, prevCanEmit; // used if 1 or more detector modules are installed

    // when a player wants to configure an installed module, this tracks the module & filter slot
    // numbers received from the client-side GUI for that player
//    private final Map<UUID, Pair<Integer, Integer>> playerToSlot = new HashMap<>();

    private int redstonePower = -1;  // current redstone power (updated via onNeighborChange())
    private int lastPower;  // tracks previous redstone power level for pulse mode
    private boolean active;  // tracks active state of router
    private int activeTimer = 0;  // used in PULSE mode to time out the active state
    private final Set<UUID> permitted = Sets.newHashSet(); // permitted user ID's from security upgrade
    private byte sidesOpen;   // bitmask of which of the 6 sides are currently open
    private boolean ecoMode = false;  // track eco-mode
    private int ecoCounter = ConfigHandler.ROUTER.ecoTimeout.get();
    private boolean hasPulsedModules = false;
    private NBTTagCompound extData;  // extra (persisted) data which various modules can set & read
    private IBlockState camouflage = null;  // block to masquerade as, set by Camo Upgrade
    private int tunedSyncValue = -1; // for synchronisation tuning, set by Sync Upgrade
    private boolean executing;  // are we currently executing modules?

    public TileEntityItemRouter() {
        super(ObjectRegistry.ITEM_ROUTER_TILE);
    }

    public IItemHandler getBuffer() {
        return bufferHandler;
    }

    public IItemHandler getModules() {
        return modulesHandler;
    }

    public IItemHandler getUpgrades() {
        return upgradesHandler;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound compound = new NBTTagCompound();

        compound.putInt("x", pos.getX());
        compound.putInt("y", pos.getY());
        compound.putInt("z", pos.getZ());

        // these fields are needed for rendering
        compound.putInt(NBT_MUFFLERS, getUpgradeCount(ObjectRegistry.MUFFLER_UPGRADE));
        compound.putBoolean(NBT_ACTIVE, active);
        compound.putByte(NBT_SIDES, sidesOpen);
        if (camouflage != null) {
            compound.put(CamouflageUpgrade.NBT_STATE_NAME, NBTUtil.writeBlockState(camouflage));
        }
        return compound;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        processClientSync(tag);
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(this.pos, -1, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        processClientSync(pkt.getNbtCompound());
    }

    private void processClientSync(NBTTagCompound compound) {
        // called client-side
        int mufflers = compound.getInt(NBT_MUFFLERS);
        if (mufflers < 3 && getUpgradeCount(ObjectRegistry.MUFFLER_UPGRADE) >= 3
                || mufflers >= 3 && getUpgradeCount(ObjectRegistry.MUFFLER_UPGRADE) < 3) {
            upgradeCount.put(ObjectRegistry.MUFFLER_UPGRADE.getRegistryName(), mufflers);
            getWorld().markBlockRangeForRenderUpdate(pos, pos);
        }

        // these fields are needed for rendering
        boolean newActive = compound.getBoolean(NBT_ACTIVE);
        byte newSidesOpen = compound.getByte(NBT_SIDES);
        boolean newEco = compound.getBoolean(NBT_ECO_MODE);
        IBlockState camo = CamouflageUpgrade.readFromNBT(compound);
        setActive(newActive);
        setSidesOpen(newSidesOpen);
        setEcoMode(newEco);
        setCamouflage(camo);
    }

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable EnumFacing side) {
        if (cap == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return inventoryCap.cast();
        } else if (cap == CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY) {
            return bufferHandler.getFluidCapability().cast();
        } else if (cap == CapabilityEnergy.ENERGY) {
            return bufferHandler.getEnergyCapability().cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void read(NBTTagCompound nbt) {
        super.read(nbt);
        bufferHandler.deserializeNBT(nbt.getCompound(NBT_BUFFER));
        modulesHandler.deserializeNBT(nbt.getCompound(NBT_MODULES));
        upgradesHandler.deserializeNBT(nbt.getCompound(NBT_UPGRADES));
        try {
            redstoneBehaviour = RouterRedstoneBehaviour.valueOf(nbt.getString(NBT_REDSTONE_MODE));
        } catch (IllegalArgumentException e) {
            // shouldn't ever happen...
            redstoneBehaviour = RouterRedstoneBehaviour.ALWAYS;
        }
        active = nbt.getBoolean(NBT_ACTIVE);
        activeTimer = nbt.getInt(NBT_ACTIVE_TIMER);
        ecoMode = nbt.getBoolean(NBT_ECO_MODE);

        NBTTagCompound ext = nbt.getCompound(NBT_EXTRA);
        NBTTagCompound ext1 = getExtData();
        for (String key : ext.keySet()) {
            ext1.put(key, ext.get(key));
        }

        // When restoring, give the counter a random initial value to avoid all saved routers
        // having the same counter and firing simultaneously, which could conceivably cause lag
        // spikes if there are many routers in the world.
        // The -1 value indicates that a random value should be picked at the next compile.
        counter = -1;
    }

    @Nonnull
    @Override
    public NBTTagCompound write(NBTTagCompound nbt) {
        nbt = super.write(nbt);
        nbt.put(NBT_BUFFER, bufferHandler.serializeNBT());
        nbt.put(NBT_MODULES, modulesHandler.serializeNBT());
        nbt.put(NBT_UPGRADES, upgradesHandler.serializeNBT());
        nbt.putString(NBT_REDSTONE_MODE, redstoneBehaviour.name());
        nbt.putBoolean(NBT_ACTIVE, active);
        nbt.putInt(NBT_ACTIVE_TIMER, activeTimer);
        nbt.putBoolean(NBT_ECO_MODE, ecoMode);

        NBTTagCompound ext = new NBTTagCompound();
        NBTTagCompound ext1 = getExtData();
        for (String key : ext1.keySet()) {
            ext.put(key, ext1.get(key));
        }
        nbt.put(NBT_EXTRA, ext);

        return nbt;
    }

    @Override
    public void tick() {
        if (recompileNeeded != 0) {
            compile();
        }

        if (getWorld().isRemote) {
            return;
        }

        counter++;
        pulseCounter++;

        if (getRedstoneBehaviour() == RouterRedstoneBehaviour.PULSE) {
            // pulse checking is done by checkRedstonePulse() - called from BlockItemRouter#neighborChanged()
            // however, we do need to turn the state inactive after a short time if we were set active by a pulse
            if (activeTimer > 0) {
                if (--activeTimer == 0) {
                    setActive(false);
                }
            }
        } else {
            if (counter >= getTickRate()) {
                allocateFluidTransfer(counter);
                executeModules(false);
                counter = 0;
            }
        }

        if (ecoMode) {
            if (active) {
                ecoCounter = ConfigHandler.ROUTER.ecoTimeout.get();
            } else if (ecoCounter > 0) {
                ecoCounter--;
            }
        }
    }

    private void executeModules(boolean pulsed) {
        executing = true;

        boolean newActive = false;

        boolean powered = pulsed || getRedstonePower() > 0;

        if (redstoneBehaviour.shouldRun(powered, pulsed)) {
            if (prevCanEmit || canEmit) {
                Arrays.fill(newRedstoneLevels, 0);
                Arrays.fill(newSignalType, SignalType.NONE);
            }
            for (CompiledModule cm : compiledModules) {
                if (cm != null && cm.hasTarget() && cm.shouldRun(powered, pulsed) && cm.execute(this)) {
                    newActive = true;
                    if (cm.termination()) {
                        break;
                    }
                }
            }
            if (prevCanEmit || canEmit) {
                handleRedstoneEmission();
            }
        }
        setActive(newActive);
        prevCanEmit = canEmit;
        executing = false;
    }

    public int getTickRate() {
        return ecoMode && ecoCounter == 0 ? ConfigHandler.ROUTER.lowPowerTickRate.get() : tickRate;
    }

    public RouterRedstoneBehaviour getRedstoneBehaviour() {
        return redstoneBehaviour;
    }

    public void setRedstoneBehaviour(RouterRedstoneBehaviour redstoneBehaviour) {
        this.redstoneBehaviour = redstoneBehaviour;
        if (redstoneBehaviour == RouterRedstoneBehaviour.PULSE) {
            lastPower = getRedstonePower();
        }
        handleSync(false);
    }

    /**
     * Check if the router processed anything on its last tick
     *
     * @return true if the router processed something
     */
    public boolean isActive() {
        return active;
    }

    private void setActive(boolean newActive) {
        if (active != newActive) {
            active = newActive;
            handleSync(true);
        }
    }

    public boolean isSideOpen(RelativeDirection side) {
        return (sidesOpen & side.getMask()) != 0;
    }

    private void setSidesOpen(byte sidesOpen) {
        if (this.sidesOpen != sidesOpen) {
            this.sidesOpen = sidesOpen;
            handleSync(true);
        }
    }

    public void setEcoMode(boolean newEco) {
        if (newEco != ecoMode) {
            ecoMode = newEco;
            ecoCounter = ConfigHandler.ROUTER.ecoTimeout.get();
            handleSync(false);
        }
    }

    @Override
    public IBlockState getCamouflage() {
        return camouflage;
    }

    @Override
    public void setCamouflage(IBlockState newCamouflage) {
        if (newCamouflage != camouflage) {
            this.camouflage = newCamouflage;
            handleSync(true);
        }
    }

    private void handleSync(boolean renderUpdate) {
        // some tile entity field changed that the client needs to know about
        // if on server, sync TE data to client; if on client, possibly mark the TE for re-render
        if (!getWorld().isRemote) {
            getWorld().notifyBlockUpdate(pos, getWorld().getBlockState(pos), getWorld().getBlockState(pos), 3);
        } else if (getWorld().isRemote && renderUpdate) {
            getWorld().markBlockRangeForRenderUpdate(pos, pos);
        }
    }

    /**
     * Compile installed modules & upgrades etc. into internal data for faster execution
     */
    private void compile() {
        if (getWorld().isRemote) {
            return;
        }

        compileModules();
        compileUpgrades();

        if (tunedSyncValue >= 0) {
            // router has a sync upgrade - init the counter accordingly
            counter = calculateSyncCounter();
        } else if (counter < 0) {
            // we've just restored from NBT - start off with a random counter value
            // to avoid lots of routers all ticking at the same time
            counter = new Random().nextInt(tickRate);
        }

        if (recompileNeeded != 0) {
            IBlockState state = getWorld().getBlockState(pos);
            getWorld().notifyBlockUpdate(pos, state, state, 3);
            getWorld().notifyNeighborsOfStateChange(pos, state.getBlock());
            markDirty();
            recompileNeeded = 0;
        }
    }

    private void compileModules() {
        if ((recompileNeeded & COMPILE_MODULES) != 0) {
            setHasPulsedModules(false);
            byte newSidesOpen = 0;
            for (CompiledModule cm : compiledModules) {
                cm.cleanup(this);
            }
            compiledModules.clear();
            for (int i = 0; i < N_MODULE_SLOTS; i++) {
                ItemStack stack = modulesHandler.getStackInSlot(i);
                if (stack.getItem() instanceof ItemModule) {
                    CompiledModule cms = ((ItemModule) stack.getItem()).compile(this, stack);
                    compiledModules.add(cms);
                    cms.onCompiled(this);
                    newSidesOpen |= cms.getDirection().getMask();
                }
            }
            moduleCount = compiledModules.size();
            setSidesOpen(newSidesOpen);
        }
    }

    private void compileUpgrades() {
        if ((recompileNeeded & COMPILE_UPGRADES) != 0) {
            upgradeCount.clear();
            totalUpgradeCount = 0;
            permitted.clear();
            setCamouflage(null);
            tunedSyncValue = -1;
            for (int i = 0; i < N_UPGRADE_SLOTS; i++) {
                ItemStack stack = upgradesHandler.getStackInSlot(i);
                if (stack.getItem() instanceof ItemUpgrade) {
                    upgradeCount.put(stack.getItem().getRegistryName(), getUpgradeCount(stack.getItem()) + stack.getCount());
                    totalUpgradeCount += stack.getCount();
                    ((ItemUpgrade) stack.getItem()).onCompiled(stack, this);
                }
            }

            itemsPerTick = 1 << (Math.min(6, getUpgradeCount(ObjectRegistry.STACK_UPGRADE)));
            tickRate = Math.max(ConfigHandler.ROUTER.hardMinTickRate.get(),
                    ConfigHandler.ROUTER.baseTickRate.get() - ConfigHandler.ROUTER.ticksPerUpgrade.get() * getUpgradeCount(ObjectRegistry.SPEED_UPGRADE));
            fluidTransferRate = Math.min(ConfigHandler.ROUTER.fluidMaxTransferRate.get(),
                    ConfigHandler.ROUTER.fluidBaseTransferRate.get() + getUpgradeCount(ObjectRegistry.FLUID_UPGRADE) * ConfigHandler.ROUTER.mBperFluidUpgade.get());
        }
    }

    public void setTunedSyncValue(int newValue) {
        tunedSyncValue = newValue;
    }

    private int calculateSyncCounter() {
        // use our global tick counter and router's tick rate to determine a value
        // for the sync counter that ensures the router always executes at a certain time
        int compileTime = (int) TickEventHandler.TickCounter % tickRate;
        int tuning = tunedSyncValue % tickRate;
        int delta = tuning - compileTime;
        if (delta <= 0) delta += tickRate;

        return tickRate - delta;
    }

    public void setAllowRedstoneEmission(boolean allow) {
        canEmit = allow;
        getWorld().setBlockState(pos, getWorld().getBlockState(pos).with(BlockItemRouter.CAN_EMIT, canEmit));
    }

    public int getModuleCount() {
        return moduleCount;
    }

    public int getUpgradeCount() {
        return totalUpgradeCount;
    }

    public int getUpgradeCount(Item type) {
        return upgradeCount.getOrDefault(type.getRegistryName(), 0);
    }

    public void recompileNeeded(int what) {
        recompileNeeded |= what;
    }

    public int getItemsPerTick() {
        return itemsPerTick;
    }

    private void allocateFluidTransfer(int ticks) {
        // increment the in/out fluid transfer allowance based on the number of ticks which have passed
        // and the current fluid transfer rate of the router (which depends on the number of fluid upgrades)
        int maxTransfer = ConfigHandler.ROUTER.baseTickRate.get() * fluidTransferRate;
        fluidTransferRemainingIn = Math.min(fluidTransferRemainingIn + ticks * fluidTransferRate, maxTransfer);
        fluidTransferRemainingOut = Math.min(fluidTransferRemainingOut + ticks * fluidTransferRate, maxTransfer);
    }

    public int getFluidTransferRate() {
        return fluidTransferRate;
    }

    public int getCurrentFluidTransferAllowance(FluidModule.FluidDirection dir) {
        return dir == FluidModule.FluidDirection.IN ? fluidTransferRemainingIn : fluidTransferRemainingOut;
    }

    public void transferredFluid(int amount, FluidModule.FluidDirection dir) {
        switch (dir) {
            case IN:
                if (fluidTransferRemainingIn < amount) ModularRouters.LOGGER.warn("fluid transfer: " + fluidTransferRemainingIn + " < " + amount);
                fluidTransferRemainingIn = Math.max(0, fluidTransferRemainingIn - amount);
                break;
            case OUT:
                if (fluidTransferRemainingOut < amount) ModularRouters.LOGGER.warn("fluid transfer: " + fluidTransferRemainingOut + " < " + amount);
                fluidTransferRemainingOut = Math.max(0, fluidTransferRemainingOut - amount);
                break;
            default:
                break;
        }
    }

    public EnumFacing getAbsoluteFacing(RelativeDirection direction) {
        IBlockState state = getWorld().getBlockState(pos);
        return direction.toEnumFacing(state.get(BlockItemRouter.FACING));
    }

    public ItemStack getBufferItemStack() {
        return bufferHandler.getStackInSlot(0);
    }

//    public void playerConfiguringModule(EntityPlayer player, int slotIndex, int filterIndex) {
//        if (slotIndex >= 0) {
//            playerToSlot.put(player.getUniqueID(), Pair.of(slotIndex, filterIndex));
//        } else {
//            playerToSlot.remove(player.getUniqueID());
//        }
//    }
//
//    public void playerConfiguringModule(EntityPlayer player, int slotIndex) {
//        playerConfiguringModule(player, slotIndex, -1);
//    }
//
//    public void clearConfigSlot(EntityPlayer player) {
//        playerToSlot.remove(player.getUniqueID());
//    }
//
//    public int getModuleConfigSlot(EntityPlayer player) {
//        if (playerToSlot.containsKey(player.getUniqueID())) {
//            return playerToSlot.get(player.getUniqueID()).getLeft();
//        } else {
//            return -1;
//        }
//    }
//
//    public int getFilterConfigSlot(EntityPlayer player) {
//        if (playerToSlot.containsKey(player.getUniqueID())) {
//            return playerToSlot.get(player.getUniqueID()).getRight();
//        } else {
//            return -1;
//        }
//    }
//
//    public ItemStack getConfiguringModule(EntityPlayer player) {
//        return playerToSlot.containsKey(player.getUniqueID()) ?
//                modulesHandler.getStackInSlot(playerToSlot.get(player.getUniqueID()).getLeft()) : ItemStack.EMPTY;
//    }
//
//    public ItemStack getConfiguringFilter(EntityPlayer player) {
//        ItemStack stack = getConfiguringModule(player);
//        if (stack.isEmpty()) return ItemStack.EMPTY;
//        int filterIdx = getFilterConfigSlot(player);
//        return filterIdx < 0 ? ItemStack.EMPTY : new BaseModuleHandler.ModuleFilterHandler(stack).getStackInSlot(filterIdx);
//    }

    public void checkForRedstonePulse() {
        redstonePower = calculateIncomingRedstonePower(pos);
        if (executing) {
            return;  // avoid recursion from executing module triggering more block updates
        }
        if (redstoneBehaviour == RouterRedstoneBehaviour.PULSE
                || hasPulsedModules && redstoneBehaviour == RouterRedstoneBehaviour.ALWAYS) {
            if (redstonePower > lastPower && pulseCounter >= tickRate) {
                allocateFluidTransfer(Math.min(pulseCounter, ConfigHandler.ROUTER.baseTickRate.get()));
                executeModules(true);
                pulseCounter = 0;
                if (active) {
                    activeTimer = tickRate;
                }
            }
            lastPower = redstonePower;
        }
    }

    public void emitRedstone(RelativeDirection direction, int power, SignalType signalType) {
        if (direction == RelativeDirection.NONE) {
            Arrays.fill(newRedstoneLevels, power);
            Arrays.fill(newSignalType, signalType);
        } else {
            EnumFacing facing = getAbsoluteFacing(direction).getOpposite();
            newRedstoneLevels[facing.ordinal()] = power;
            newSignalType[facing.ordinal()] = signalType;
        }
    }

    public int getRedstoneLevel(EnumFacing facing, boolean strong) {
        if (!canEmit) {
            // -1 means the block shouldn't have any special redstone handling
            return -1;
        }
        int i = facing.ordinal();
        if (strong) {
            return signalType[i] == SignalType.STRONG ? redstoneLevels[i] : 0;
        } else {
            return signalType[i] != SignalType.NONE ? redstoneLevels[i] : 0;
        }
    }

    private void handleRedstoneEmission() {
        boolean notifyOwnNeighbours = false;
        EnumSet<EnumFacing> toNotify = EnumSet.noneOf(EnumFacing.class);

        if (!canEmit) {
            // block has stopped being able to emit a signal (all detector modules removed)
            // notify neighbours, and neighbours of neighbours where a strong signal was being emitted
            notifyOwnNeighbours = true;
            for (EnumFacing f : EnumFacing.values()) {
                if (signalType[f.ordinal()] == SignalType.STRONG) {
                    toNotify.add(f.getOpposite());
                }
            }
            Arrays.fill(redstoneLevels, 0);
            Arrays.fill(signalType, SignalType.NONE);
        } else {
            for (EnumFacing facing : EnumFacing.values()) {
                int i = facing.ordinal();
                // if the signal op (strong/weak) has changed, notify neighbours of block in that direction
                // if the signal strength has changed, notify immediate neighbours
                //   - and if signal op is strong, also notify neighbours of neighbour
                if (newSignalType[i] != signalType[i]) {
                    toNotify.add(facing.getOpposite());
                    signalType[i] = newSignalType[i];
                }
                if (newRedstoneLevels[i] != redstoneLevels[i]) {
                    notifyOwnNeighbours = true;
                    if (newSignalType[i] == SignalType.STRONG) {
                        toNotify.add(facing.getOpposite());
                    }
                    redstoneLevels[i] = newRedstoneLevels[i];
                }
            }
        }

        for (EnumFacing f : toNotify) {
            BlockPos pos2 = pos.offset(f);
            getWorld().notifyNeighborsOfStateChange(pos2, getWorld().getBlockState(pos2).getBlock());
        }
        if (notifyOwnNeighbours) {
            getWorld().notifyNeighborsOfStateChange(pos, getWorld().getBlockState(pos).getBlock());
        }
    }

    public void addPermittedIds(Set<UUID> permittedIds) {
        this.permitted.addAll(permittedIds);
    }

    public boolean isPermitted(EntityPlayer player) {
        if (permitted.isEmpty() || permitted.contains(player.getUniqueID())) {
            return true;
        }
        for (EnumHand hand : EnumHand.values()) {
            if (player.getHeldItem(hand).getItem() == ObjectRegistry.OVERRIDE_CARD) {
                return true;
            }
        }
        return false;
    }

    public boolean isBufferFull() {
        ItemStack stack = bufferHandler.getStackInSlot(0);
        return !stack.isEmpty() && stack.getCount() >= stack.getMaxStackSize();
    }

    public boolean isBufferEmpty() {
        return bufferHandler.getStackInSlot(0).isEmpty();
    }

    public ItemStack peekBuffer(int amount) {
        return bufferHandler.extractItem(0, amount, true);
    }

    public ItemStack extractBuffer(int amount) {
        return bufferHandler.extractItem(0, amount, false);
    }

    public ItemStack insertBuffer(ItemStack stack) {
        return bufferHandler.insertItem(0, stack, false);
    }

    public void setBufferItemStack(ItemStack stack) {
        bufferHandler.setStackInSlot(0, stack);
    }

    public boolean getEcoMode() {
        return ecoMode;
    }

    public void setHasPulsedModules(boolean hasPulsedModules) {
        this.hasPulsedModules = hasPulsedModules;
    }

    public int getRedstonePower() {
        if (redstonePower < 0) {
            redstonePower = calculateIncomingRedstonePower(pos);
        }
        return redstonePower;
    }

    private int calculateIncomingRedstonePower(BlockPos pos) {
        // like World#isBlockIndirectlyGettingPowered() but will ignore redstone from any sides
        // currently being extruded on
        int power = 0;
        for (EnumFacing facing : EnumFacing.values()) {
            if (getExtData().getInt(CompiledExtruderModule.NBT_EXTRUDER_DIST + facing) > 0) {
                // ignore signal from any side we're extruding on (don't let placed redstone emitters lock up the router)
                continue;
            }
            int p = getWorld().getRedstonePower(pos.offset(facing), facing);
            if (p >= 15) {
                return p;
            } else if (p > power) {
                power = p;
            }
        }
        return power;
    }

    public NBTTagCompound getExtData() {
        if (extData == null) {
            extData = new NBTTagCompound();
        }
        return extData;
    }

    public static TileEntityItemRouter getRouterAt(IBlockReader world, BlockPos routerPos) {
//        TileEntity te = world instanceof ChunkCache ?
//                ((ChunkCache) world).getTileEntity(routerPos, Chunk.EnumCreateEntityType.CHECK) :
//                world.getTileEntity(routerPos);
        TileEntity te = world.getTileEntity(routerPos);
        return te instanceof TileEntityItemRouter ? (TileEntityItemRouter) te : null;
    }

    public void playSound(EntityPlayer player, BlockPos pos, SoundEvent sound, SoundCategory category, float volume, float pitch) {
        if (getUpgradeCount(ObjectRegistry.MUFFLER_UPGRADE) == 0) {
            getWorld().playSound(player, pos, sound, category, volume, pitch);
        }
    }

    public void notifyModules() {
        for (CompiledModule cm : compiledModules) {
            cm.onNeighbourChange(this);
        }
    }

    public int getModuleSlotCount() {
        return N_MODULE_SLOTS;
    }

    public int getUpgradeSlotCount() {
        return N_UPGRADE_SLOTS;
    }

    public int getBufferSlotCount() {
        return N_BUFFER_SLOTS;
    }

    @Override
    public Container createContainer(InventoryPlayer inventoryPlayer, EntityPlayer entityPlayer) {
        return new ContainerItemRouter(inventoryPlayer, this);
    }

    @Override
    public String getGuiID() {
        return getType().getRegistryName().toString();
    }

    @Override
    public ITextComponent getName() {
        return new TextComponentString(getGuiID());
    }

    @Override
    public boolean hasCustomName() {
        return false;
    }

    @Nullable
    @Override
    public ITextComponent getCustomName() {
        return null;
    }
}
