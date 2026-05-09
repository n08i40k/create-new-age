package org.antarcticgardens.cna.content.motor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.compat.computercraft.AbstractComputerBehaviour;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.utility.CreateLang;
import com.tterrag.registrate.builders.BlockEntityBuilder;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.antarcticgardens.cna.CNABlockEntityTypes;
import org.antarcticgardens.cna.compat.computercraft.CNAComputerCraftProxy;
import org.antarcticgardens.cna.config.CNAConfig;
import org.antarcticgardens.cna.content.motor.extension.MotorExtensionBlockEntity;
import org.antarcticgardens.cna.content.motor.variants.AdvancedMotorVariant;
import org.antarcticgardens.cna.content.motor.variants.BasicMotorVariant;
import org.antarcticgardens.cna.content.motor.variants.IMotorVariant;
import org.antarcticgardens.cna.content.motor.variants.ReinforcedMotorVariant;
import org.antarcticgardens.cna.util.RunnableUtil;
import org.antarcticgardens.cna.util.StringFormatUtil;
import org.antarcticgardens.esl.energy.EnergyStorage;
import org.antarcticgardens.esl.energy.SimpleEnergyStorage;

import java.util.List;

public class MotorBlockEntity extends GeneratingKineticBlockEntity implements IHaveGoggleInformation {
    private final SimpleEnergyStorage storage;

    public boolean needsPower = false;
    private final IMotorVariant variant;
    public final int tier;
    public MotorScrollValueBehaviour speedBehavior;
    public boolean powered = false;
    private float actualSpeed = 0;
    private float actualStress = 0;
    private long prvEnergy = -100000;

    public AbstractComputerBehaviour computerBehaviour;

    private float speed = 0;
    private float stress = 0;

    public MotorBlockEntity(BlockEntityType<?> arg, BlockPos arg2, BlockState arg3, IMotorVariant variant) {
        super(arg, arg2, arg3);
        this.variant = variant;
        switch (variant) {
            case BasicMotorVariant basicMotorVariant -> this.tier = 1;
            case AdvancedMotorVariant advancedMotorVariant -> this.tier = 2;
            case ReinforcedMotorVariant reinforcedMotorVariant -> this.tier = 3;
            case null, default -> this.tier = 0;
        }

        storage = new SimpleEnergyStorage(variant.getMaxCapacity())
                .onFinalCommit(RunnableUtil.createBlockEntityUpdater(this))
                .setSupportsExtraction(false);

        EnergyStorage.registerForBlockEntity((blockEntity, direction) -> blockEntity.storage, CNABlockEntityTypes.BASIC_MOTOR.get());
        EnergyStorage.registerForBlockEntity((blockEntity, direction) -> blockEntity.storage, CNABlockEntityTypes.ADVANCED_MOTOR.get());
        EnergyStorage.registerForBlockEntity((blockEntity, direction) -> blockEntity.storage, CNABlockEntityTypes.REINFORCED_MOTOR.get());
    }

    public static BlockEntityBuilder.BlockEntityFactory<MotorBlockEntity> create(IMotorVariant variant) {
        return (type, pos, state) -> new MotorBlockEntity(type, pos, state, variant);
    }

    @Override
    public void initialize() {
        powered = getLevel().hasNeighborSignal(this.getBlockPos());
        super.initialize();
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        speedBehavior = new MotorScrollValueBehaviour(CreateLang.translateDirect("kinetics.creative_motor.rotation_speed"), this, new MotorValueBox());
        speedBehavior.requiresWrench();
        speedBehavior.value = getDefaultSpeed();
        speedBehavior.withCallback(i -> this.updateGeneratedRotation());
        behaviours.add(speedBehavior);
        behaviours.add(computerBehaviour = CNAComputerCraftProxy.behaviour(this));
    }

    @Override
    public void invalidate() {
        super.invalidate();
        computerBehaviour.removePeripheral();
    }

    static class MotorValueBox extends ValueBoxTransform.Sided {

        @Override
        protected Vec3 getSouthLocation() {
            return VecHelper.voxelSpace(8, 8, 12.5);
        }

        @Override
        public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
            Direction facing = state.getValue(CreativeMotorBlock.FACING);
            return super.getLocalOffset(level, pos, state).add(Vec3.atLowerCornerOf(facing.getNormal())
                    .scale(-1 / 16f));
        }

        @Override
        public void rotate(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
            super.rotate(level, pos, state, ms);
            Direction facing = state.getValue(CreativeMotorBlock.FACING);
            if (facing.getAxis() == Direction.Axis.Y)
                return;
            if (getSide() != Direction.UP)
                return;
            TransformStack.of(ms)
                    .rotateZDegrees(-AngleHelper.horizontalAngle(facing) + 180);
        }

        @Override
        protected boolean isSideActive(BlockState state, Direction direction) {
            Direction facing = state.getValue(CreativeMotorBlock.FACING);
            if (facing.getAxis() != Direction.Axis.Y && direction == Direction.DOWN)
                return false;
            return direction.getAxis() != facing.getAxis();
        }

    }


    public int getDefaultSpeed() {
        return 16;
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        storage.setStoredEnergy(compound.getLong("energy"));
        actualSpeed = compound.getFloat("aSpeed");
        needsPower = compound.getBoolean("needsPower");
        stress = compound.getFloat("lastGeneratedStress");
        speed = compound.getFloat("lastGeneratedSpeed");
        e = compound.getLong("eUse");
        actualStress = compound.getFloat("actualStress");
        super.read(compound, registries, clientPacket);
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        compound.putLong("energy", storage.getStoredEnergy());
        compound.putFloat("aSpeed", actualSpeed);
        compound.putBoolean("needsPower", needsPower);
        compound.putFloat("lastGeneratedStress", stress);
        compound.putFloat("lastGeneratedSpeed", speed);
        compound.putFloat("eUse", e);
        compound.putFloat("actualStress", actualStress);
        super.write(compound, registries, clientPacket);
    }

    @Override
    public float calculateStressApplied() {
        return 0;
    }

    private long e;

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        CreateLang.translate("tooltip.create_new_age.energy_stored")
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);

        CreateLang.translate("tooltip.create_new_age.energy_storage", StringFormatUtil.formatLong(storage.getStoredEnergy()),
                        StringFormatUtil.formatLong(storage.getCapacity()))
                .style(ChatFormatting.AQUA)
                .forGoggles(tooltip, 1);

        CreateLang.translate("tooltip.create_new_age.using")
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);

        CreateLang.translate("tooltip.create_new_age.energy_per_tick", StringFormatUtil.formatLong(e))
                .style(ChatFormatting.AQUA)
                .forGoggles(tooltip, 1);

        super.addToGoggleTooltip(tooltip, isPlayerSneaking);

        return true;
    }

    @Override
    public float calculateAddedStressCapacity() {
        if (level != null && level.isClientSide()) {
            return this.lastCapacityProvided;
        }

        this.lastCapacityProvided = actualStress / actualSpeed;
        this.lastCapacityProvided = Float.isNaN(this.lastCapacityProvided) || Float.isInfinite(this.lastCapacityProvided) ? 0 : Math.abs(this.lastCapacityProvided);
        return this.lastCapacityProvided;
    }

    @Override
    public float getGeneratedSpeed() {
        return actualSpeed;
    }

    private void clearGhostKineticInformation() {
        if (level == null || level.isClientSide || actualSpeed == 0 || hasSource())
            return;

        if (hasNetwork() && createNetworkId().equals(network))
            return;

        clearKineticInformation();
        speed = 0;
        stress = 0;
    }

    public void updateGeneratedRotation() {
        float speed = getGeneratedSpeed();
        float prevSpeed = this.speed;

        if (level == null || level.isClientSide)
            return;

        if (prevSpeed != speed) {
            if (!hasSource()) {
                IRotate.SpeedLevel levelBefore = IRotate.SpeedLevel.of(this.speed);
                IRotate.SpeedLevel levelafter = IRotate.SpeedLevel.of(speed);
                if (levelBefore != levelafter)
                    effects.queueRotationIndicators();
            }

            applyNewSpeed(prevSpeed, speed);
        }

        if (hasNetwork() && speed != 0) {
            KineticNetwork network = getOrCreateNetwork();
            network.updateCapacityFor(this, stress);
            notifyStressCapacityChange(calculateAddedStressCapacity());
            getOrCreateNetwork().updateStressFor(this, calculateStressApplied());
            network.updateStress();
        }

        onSpeedChanged(prevSpeed);

        sendData();
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null)
            return;

        float stressMultiplier = 1;
        long extraEnergy = 0;

        Direction dir = this.getBlockState().getValue(DirectionalKineticBlock.FACING);
        if (level.getBlockState(getBlockPos().relative(dir.getOpposite())).getOptionalValue(DirectionalKineticBlock.FACING).orElse(dir.getOpposite()) == dir
                && level.getBlockEntity(getBlockPos().relative(dir.getOpposite()))
                instanceof MotorExtensionBlockEntity extension) {
            stressMultiplier = extension.getMultiplier();
            extraEnergy = extension.getVariant().getExtraCapacity();
        }

        storage.setCapacity(extraEnergy + variant.getMaxCapacity());
        speedBehavior.betweenValidated((int) -variant.getSpeed(), (int) variant.getSpeed());

        if (!level.isClientSide()) {
            clearGhostKineticInformation();

            int needed = (int) Math.ceil((variant.getStress() * stressMultiplier
                        * CNAConfig.getServer().motorSUMultiplier.get())
                    * CNAConfig.getServer().suToEnergy.get());
            e = needsPower == powered ? storage.internalExtract(needed, false) : 0;
            if (e > 0) {
                actualSpeed = speedBehavior.value;
                actualStress =
                        (float) Math.ceil((variant.getStress() * stressMultiplier
                                    * CNAConfig.getServer().motorSUMultiplier.get())
                                * (e / (float)needed));
            } else {
                actualSpeed = 0;
                actualStress = 0;
            }
            if (((actualSpeed != speed) || (actualStress != stress))) {
                updateGeneratedRotation();
                speed = actualSpeed;
                stress = actualStress;
            } else if (storage.getStoredEnergy() != prvEnergy && level.getGameTime() % 20 == 0) {
                this.sendData();
                prvEnergy = storage.getStoredEnergy();
            }
        }
    }
}
