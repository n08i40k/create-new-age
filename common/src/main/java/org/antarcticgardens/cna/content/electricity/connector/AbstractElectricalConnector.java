package org.antarcticgardens.cna.content.electricity.connector;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.antarcticgardens.cna.config.CNAConfig;
import org.antarcticgardens.cna.content.electricity.network.ElectricalNetwork;
import org.antarcticgardens.cna.content.electricity.wire.WireType;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractElectricalConnector extends SmartBlockEntity {
    private static final int MOVED_CONNECTION_REPAIR_DELAY = 2;

    protected final Map<AbstractElectricalConnector, WireType> connectors = new HashMap<>();
    protected final Map<BlockPos, WireType> connectorPositions = new HashMap<>();

    protected ElectricalNetwork network;

    protected boolean connectionsInitialized = false;
    boolean needsInstanceUpdate = true;
    private BlockPos positionBeforeMove;
    private int movedConnectionRepairDelay;

    public AbstractElectricalConnector(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        ListTag list = new ListTag();

        for (Map.Entry<BlockPos, WireType> e : connectorPositions.entrySet()) {
            CompoundTag compound = new CompoundTag();
            compound.put("position", NBTHelper.writeVec3i(e.getKey()));
            compound.put("wire", StringTag.valueOf(e.getValue().name()));

            list.add(compound);
        }

        tag.put("connections", list);
        tag.put("connectorPosition", NBTHelper.writeVec3i(getBlockPos()));
        super.write(tag, registries, clientPacket);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        ListTag list = tag.getList("connections", Tag.TAG_COMPOUND);
        connectorPositions.clear();
        positionBeforeMove = null;
        movedConnectionRepairDelay = 0;

        if (tag.contains("connectorPosition")) {
            BlockPos savedPosition = new BlockPos(NBTHelper.readVec3i((ListTag) tag.get("connectorPosition")));
            if (!savedPosition.equals(getBlockPos())) {
                positionBeforeMove = savedPosition;
                scheduleMovedConnectionRepair(MOVED_CONNECTION_REPAIR_DELAY);
            }
        }

        for (Tag listTag : list.toArray(new Tag[0])) {
            if (listTag instanceof CompoundTag ct && ct.contains("position") && ct.contains("wire")) {
                BlockPos pos = new BlockPos(NBTHelper.readVec3i((ListTag) ct.get("position")));
                WireType wire = WireType.valueOf(ct.getString("wire"));

                connectorPositions.put(pos, wire);
            }
        }

        needsInstanceUpdate = true;
        super.read(tag, registries, clientPacket);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    public Map<BlockPos, WireType> getConnectorPositions() {
        return Collections.unmodifiableMap(connectorPositions);
    }

    public BlockPos getSupportingBlockPos() {
        return this.getBlockPos();
    }

    public abstract Direction getFacing();

    protected void serverTick() {
        if (positionBeforeMove != null && movedConnectionRepairDelay > 0) {
            movedConnectionRepairDelay--;
            return;
        }

        ensureNetwork();

        if (!connectionsInitialized) {
            updateConnections();
            connectionsInitialized = true;
        }
    }

    public void neighborChanged() {
        if (network != null) {
            network.updateConsumersAndSources();
        }
    }

    private void updateConnections() {
        Map<BlockPos, WireType> resolvedPositions = new HashMap<>();

        for (Map.Entry<BlockPos, WireType> e : connectorPositions.entrySet()) {
            BlockPos connectionPos = resolveMovedConnection(e.getKey());
            resolvedPositions.put(connectionPos, e.getValue());
        }

        if (!resolvedPositions.equals(connectorPositions)) {
            connectorPositions.clear();
            connectorPositions.putAll(resolvedPositions);
            setChanged();
        }

        for (Map.Entry<BlockPos, WireType> e : resolvedPositions.entrySet()) {
            BlockPos connectionPos = e.getKey();
            if (getLevel().getBlockEntity(connectionPos) instanceof AbstractElectricalConnector connector) {
                if (positionBeforeMove != null)
                    connector.replaceConnectionPosition(positionBeforeMove, getBlockPos());

                connect(connector, e.getValue());
            }
        }

        positionBeforeMove = null;
        movedConnectionRepairDelay = 0;
        needsInstanceUpdate = true;
    }

    private BlockPos resolveMovedConnection(BlockPos pos) {
        if (getLevel().getBlockEntity(pos) instanceof AbstractElectricalConnector)
            return pos;

        if (positionBeforeMove == null)
            return pos;

        BlockPos movedConnectorPos = findMovedConnector(pos);
        if (movedConnectorPos != null)
            return movedConnectorPos;

        BlockPos delta = getBlockPos().subtract(positionBeforeMove);
        if (delta.equals(BlockPos.ZERO))
            return pos;

        BlockPos movedPos = pos.offset(delta);
        if (getLevel().getBlockEntity(movedPos) instanceof AbstractElectricalConnector)
            return movedPos;

        return pos;
    }

    private void scheduleMovedConnectionRepair(int delay) {
        movedConnectionRepairDelay = Math.max(movedConnectionRepairDelay, delay);
        connectionsInitialized = false;

        if (level != null && !level.isClientSide)
            level.scheduleTick(getBlockPos(), getBlockState().getBlock(), delay);
    }

    private void ensureNetwork() {
        if (network == null)
            setNetwork(new ElectricalNetwork(this));
    }

    private BlockPos findMovedConnector(BlockPos oldPos) {
        if (level == null || positionBeforeMove == null)
            return null;

        int range = Math.max(1, CNAConfig.getServer().maxWireLength.get());
        BlockPos min = getBlockPos().offset(-range, -range, -range);
        BlockPos max = getBlockPos().offset(range, range, range);

        for (BlockPos candidate : BlockPos.betweenClosed(min, max)) {
            if (level.getBlockEntity(candidate) instanceof AbstractElectricalConnector connector
                    && oldPos.equals(connector.positionBeforeMove))
                return candidate.immutable();
        }

        return null;
    }

    public void replaceConnectionPosition(BlockPos oldPos, BlockPos newPos) {
        if (oldPos.equals(newPos))
            return;

        WireType wireType = connectorPositions.remove(oldPos);
        if (wireType == null)
            return;

        connectorPositions.put(newPos, wireType);
        connectors.clear();
        connectionsInitialized = false;
        needsInstanceUpdate = true;

        if (network != null)
            network.destroy();

        setChanged();
        if (level instanceof ServerLevel serverLevel)
            serverLevel.getChunkSource().blockChanged(getBlockPos());
    }

    public void remove(Level level) {
        if (!level.isClientSide())
            network.destroy();

        for (Map.Entry<AbstractElectricalConnector, WireType> e : connectors.entrySet()) {
            e.getKey().disconnect(this);
            e.getKey().updateConnections();

            e.getKey().setChanged();

            if (level instanceof ServerLevel serverLevel) {
                serverLevel.getChunkSource().blockChanged(e.getKey().getBlockPos());

                Containers.dropContents(level, getBlockPos(), NonNullList.of(ItemStack.EMPTY, e.getValue().getDroppedItem()));
            }
        }
    }

    public void connect(AbstractElectricalConnector entity, WireType wireType) {
        entity.connectWithoutNetworking(this, wireType);
        connectWithoutNetworking(entity, wireType);

        entity.setChanged();
        setChanged();

        if (level instanceof ServerLevel serverLevel) {
            ensureNetwork();
            network.addNode(entity);

            serverLevel.getChunkSource().blockChanged(entity.getBlockPos());
            serverLevel.getChunkSource().blockChanged(getBlockPos());
        }
    }

    private void connectWithoutNetworking(AbstractElectricalConnector entity, WireType wireType) {
        if (!connectors.containsKey(entity))
            connectors.put(entity, wireType);

        if (!connectorPositions.containsKey(entity.getBlockPos()))
            connectorPositions.put(entity.getBlockPos(), wireType);
    }

    public void disconnect(AbstractElectricalConnector entity) {
        connectors.remove(entity);
        connectorPositions.remove(entity.getBlockPos());
    }

    public Map<AbstractElectricalConnector, WireType> getConnectedConnectors() {
        return Collections.unmodifiableMap(connectors);
    }

    public boolean isConnected(BlockPos pos) {
        return connectorPositions.containsKey(pos);
    }

    public void setNetwork(ElectricalNetwork network) {
        this.network = network;
    }

    public ElectricalNetwork getNetwork() {
        return network;
    }

    public Vec3 getConnectionPoint() {
        return new Vec3(0.5f, 0.5f, 0.5f);
    }
}
