package org.antarcticgardens.cna.content.electricity.wire;

import com.simibubi.create.foundation.utility.CreateLang;
import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.antarcticgardens.cna.config.CNAConfig;
import org.antarcticgardens.cna.content.electricity.connector.AbstractElectricalConnector;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static org.antarcticgardens.cna.CNADataComponents.BOUND_TO;

public class ElectricWireItem extends Item {
    private final WireType wireType;

    public ElectricWireItem(Properties properties, WireType wireType) {
        super(properties);
        this.wireType = wireType;
    }

    public static ElectricWireItem newCopperWire(Properties properties) {
        return new ElectricWireItem(properties, WireType.COPPER);
    }

    public static ElectricWireItem newIronWire(Properties properties) {
        return new ElectricWireItem(properties, WireType.OVERCHARGED_IRON);
    }

    public static ElectricWireItem newGoldenWire(Properties properties) {
        return new ElectricWireItem(properties, WireType.OVERCHARGED_GOLD);
    }

    public static ElectricWireItem newDiamondWire(Properties properties) {
        return new ElectricWireItem(properties, WireType.OVERCHARGED_DIAMOND);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(@NotNull Level level, @NotNull Player player, InteractionHand usedHand) {
        ItemStack item = player.getItemInHand(usedHand);
        BlockPos boundToPos = getBoundConnector(item);

        if (boundToPos != null && player.isShiftKeyDown()) {
            playUnboundSound(player);
            player.displayClientMessage(Component.translatable("item.create_new_age.wire.message.unbound"), true);
            item.remove(BOUND_TO);
            return InteractionResultHolder.success(item);
        }
        return InteractionResultHolder.pass(item);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (isSelected) {
            BlockPos boundToPos = getBoundConnector(stack);
            if (boundToPos == null)
                return;
            if (!(level.getBlockEntity(boundToPos) instanceof AbstractElectricalConnector)) {
                stack.remove(BOUND_TO);
            }

            int maxLength = CNAConfig.getServer().maxWireLength.get();

            if (entity.distanceToSqr(boundToPos.getX(), boundToPos.getY(), boundToPos.getZ()) > (maxLength * maxLength * 3)) {
                stack.remove(BOUND_TO);
                playUnboundSound(entity);
                if (entity instanceof Player pl)
                    pl.displayClientMessage(Component.translatable("item.create_new_age.wire.message.too_far", maxLength), true);
            }
        }
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        BlockEntity clickedEntity = context.getLevel().getBlockEntity(context.getClickedPos());
        BlockPos boundToPos = getBoundConnector(context.getItemInHand());

        if (clickedEntity instanceof AbstractElectricalConnector clickedConnector) {
            Player player = context.getPlayer();

            if (player == null)
                return InteractionResult.SUCCESS;

            if (boundToPos == null) {
                setBoundConnector(context.getItemInHand(), clickedConnector);
                playBoundSound(player);
                return InteractionResult.SUCCESS;
            } else {
                BlockPos clickedPos = clickedConnector.getBlockPos();
                int maxLength = CNAConfig.getServer().maxWireLength.get();

                Vec3 boundToPosProj = SableCompanion.INSTANCE.projectOutOfSubLevel(context.getLevel(), (Position) boundToPos.getCenter());
                Vec3 clickedPosProj = SableCompanion.INSTANCE.projectOutOfSubLevel(context.getLevel(), (Position) clickedPos.getCenter());

                if (boundToPos.equals(clickedPos)) {
                    player.displayClientMessage(Component.translatable("item.create_new_age.wire.message.self_connect"), true);
                    context.getItemInHand().remove(BOUND_TO);
                    return InteractionResult.FAIL;
                } else if (boundToPosProj.distanceTo(clickedPosProj) > maxLength) {
                    player.displayClientMessage(Component.translatable("item.create_new_age.wire.message.too_far", maxLength), true);
                    return InteractionResult.FAIL;
                } else if (clickedConnector.isConnected(boundToPos)) {
                    player.displayClientMessage(Component.translatable("item.create_new_age.wire.message.already_connected"), true);
                    context.getItemInHand().remove(BOUND_TO);
                    return InteractionResult.FAIL;
                }

                BlockEntity boundToEntity = context.getLevel().getBlockEntity(boundToPos);

                if (boundToEntity instanceof AbstractElectricalConnector boundToConnector) {
                    context.getItemInHand().remove(BOUND_TO);
                    boundToConnector.connect(clickedConnector, wireType);

                    if (!player.isCreative())
                        context.getItemInHand().shrink(1);

                    playBoundSound(player);

                    player.displayClientMessage(Component.translatable("item.create_new_age.wire.message.connected"), true);

                    return InteractionResult.CONSUME;
                } else
                    return InteractionResult.FAIL;
            }
        }

        return InteractionResult.PASS;
    }

    private void playBoundSound(Entity entity) {
        entity.playSound(SoundEvents.LEASH_KNOT_PLACE, 1.0f, 1.0f);
    }

    private void playUnboundSound(Entity entity) {
        entity.playSound(SoundEvents.LEASH_KNOT_BREAK, 1.0f, 1.0f);
    }

    public BlockPos getBoundConnector(ItemStack stack) {
        return stack.get(BOUND_TO);
    }

    public WireType getWireType() {
        return wireType;
    }

    private void setBoundConnector(ItemStack stack, AbstractElectricalConnector connector) {
        stack.set(BOUND_TO, connector.getBlockPos());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(CreateLang.translate("tooltip.create_new_age.transfers").style(ChatFormatting.GRAY)
                .component());
        tooltipComponents.add(CreateLang.text(" ").translate("tooltip.create_new_age.energy_per_tick", String.format("%,d", wireType.getConductivity())).style(ChatFormatting.AQUA).component());
    }
}
