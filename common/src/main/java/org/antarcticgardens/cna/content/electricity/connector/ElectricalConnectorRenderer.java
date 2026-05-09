package org.antarcticgardens.cna.content.electricity.connector;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.createmod.catnip.data.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.antarcticgardens.cna.CNARenderTypes;
import org.antarcticgardens.cna.CreateNewAge;
import org.antarcticgardens.cna.config.CNAConfig;
import org.antarcticgardens.cna.content.electricity.wire.ElectricWireItem;
import org.antarcticgardens.cna.content.electricity.wire.WireType;
import org.antarcticgardens.cna.util.BlockPosUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class ElectricalConnectorRenderer implements BlockEntityRenderer<AbstractElectricalConnector> {
    public ElectricalConnectorRenderer(BlockEntityRendererProvider.Context context) {
        super();
    }

    public static HitResult pickBlockFromPos(Level world, Vec3 pos, Vec3 dir, double distance) {
        Vec3 vec33 = pos.add(dir.x * distance, dir.y * distance, dir.z * distance);
        return world.clip(new ClipContext(pos, vec33, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, Minecraft.getInstance().player));
    }

    @Override
    public void render(AbstractElectricalConnector blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        renderAllConnections(blockEntity, poseStack, buffer);
        renderHand(blockEntity, partialTick, poseStack, buffer);
    }

    public void renderAllConnections(AbstractElectricalConnector blockEntity, PoseStack poseStack, MultiBufferSource buffer) {
        blockEntity.getConnectorPositions().entrySet().stream().forEach(e ->
                renderConnection(blockEntity.getBlockPos(), e.getKey(), e.getValue(), poseStack, buffer, blockEntity.getLevel()));
    }

    //Makes sure that only one of the two connectors renders the wire
    public boolean shouldRenderConnection(BlockPos pos, BlockPos endPos){
        if(pos.getX() < endPos.getX()){
            return true;
        }else if(pos.getX() == endPos.getX()) {
            if(pos.getY() < endPos.getY()){
                return true;
            }else if(pos.getY() == endPos.getY() && pos.getZ() < endPos.getZ()) {
                return true;
            }
        }
        return false;
    }

    private static void renderWire(WireType type, PoseStack poseStack, MultiBufferSource buffer, AbstractElectricalConnector origin, Position targetPos, float maxDistance) {
        var level = origin.getLevel();

        if (level == null)
            return;

        var originPos = BlockPosUtil.toVec3(origin.getBlockPos()).add(origin.getConnectionPoint());
        var middlePos = origin.getConnectionPoint().toVector3f();

        // project vectors
        var originProjPos = SableCompanion.INSTANCE.projectOutOfSubLevel(level, (Position) originPos);
        var targetProjPos = SableCompanion.INSTANCE.projectOutOfSubLevel(level, targetPos);

        float distance = (float) originProjPos.distanceTo(targetProjPos);

        if (distance >= 1_000) // hardcoded safe limit
            return;

        var originProjBlockPos = SableCompanion.INSTANCE
                .projectOutOfSubLevel(level, (Position) origin.getBlockPos().getCenter())
                .toVector3f();

        Vector3f direction = targetProjPos.subtract(originProjPos).normalize().toVector3f();
        Vector3f directionProj = direction;

        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        Vector3f upProj = up;

        // instanceof has built-in nullability check, so I used it only for inlining sublevel variable
        if (SableCompanion.INSTANCE.getContaining(origin) instanceof SubLevelAccess subLevelAccess) {
            Pose3dc pose3dc = subLevelAccess.logicalPose();

            directionProj = pose3dc.transformNormalInverse(new Vec3(direction)).toVector3f();
            upProj = pose3dc.transformNormalInverse(new Vec3(up)).toVector3f();
        }

        int sections = (int) Math.ceil(distance * CNAConfig.getClient().wireSectionsPerMeter.get());

        Wire wire = new Wire(directionProj, distance, sections, upProj);

        var texture = distance >= maxDistance
                ? ResourceLocation.fromNamespaceAndPath(CreateNewAge.MOD_ID, "textures/wire/red.png")
                : type.getTextureLocation();

        VertexConsumer consumer = buffer.getBuffer(CNARenderTypes.wire(texture));

        poseStack.pushPose();
        poseStack.translate(middlePos.x(), middlePos.y(), middlePos.z());
        poseStack.mulPose(new Matrix4f().rotateTowards(wire.getDirection(), wire.getUp()));

        for (int i = 0; i < wire.getSections().size(); i++) {
            Pair<WireSection, Float> sectionWithOffset = wire.getSections().get(i);
            var section = sectionWithOffset.getFirst();
            float yOffset = sectionWithOffset.getSecond();

            float sectionOffset = wire.getSectionLength() * i;
            Vector3f lightPos = originProjBlockPos
                    .add(direction.mul(sectionOffset))
                    .add(up.mul(yOffset));
            BlockPos lightBlockPos = BlockPos.containing(new Vec3(lightPos));
            int block = level.getBrightness(LightLayer.BLOCK, lightBlockPos);
            int sky = level.getBrightness(LightLayer.SKY, lightBlockPos);

            section.render(consumer, poseStack, LightTexture.pack(block, sky), yOffset);

            poseStack.translate(0.0f, 0.0f, wire.getSectionLength());
        }

        poseStack.popPose();
    }

    public void renderConnection(BlockPos pos, BlockPos endPos, WireType wireType, PoseStack poseStack, MultiBufferSource buffer, Level level) {
        if (!shouldRenderConnection(pos, endPos)) {
            return;
        }

        if (!(level.getBlockEntity(pos) instanceof AbstractElectricalConnector originConnector)
                || !(level.getBlockEntity(endPos) instanceof AbstractElectricalConnector endConnector))
            return;

        var targetPos = endConnector.getConnectionPoint().add(Vec3.atLowerCornerOf(endPos));
        var maxDistance = CNAConfig.getServer().maxWireLength.get() * 2;

        renderWire(wireType, poseStack, buffer, originConnector, targetPos, maxDistance);
    }

    public void renderHand(AbstractElectricalConnector blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffer) {
        LocalPlayer player = Minecraft.getInstance().player;

        if (player != null && Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
            ItemStack itemInHand = player.getMainHandItem();

            if (!(itemInHand.getItem() instanceof ElectricWireItem))
                itemInHand = player.getOffhandItem();

            if (itemInHand.getItem() instanceof ElectricWireItem wireItem) {
                Level level = player.level();

                BlockPos originBoundPos = wireItem.getBoundConnector(itemInHand);

                if (originBoundPos != null && originBoundPos.equals(blockEntity.getBlockPos())) {
                    Vec3 playerEyePos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                    Vec3 targetPos = playerEyePos.add(player.getViewVector(partialTick).normalize().scale(2.0f));

                    HitResult hit = pickBlockFromPos(blockEntity.getLevel(), playerEyePos,
                            player.getViewVector(partialTick), Minecraft.getInstance().player.blockInteractionRange());

                    if (hit instanceof BlockHitResult blockHit) {
                        Vec3 vec = playerEyePos.add(blockHit.getLocation().subtract(playerEyePos).scale(0.9f));

                        if (playerEyePos.distanceTo(targetPos) > playerEyePos.distanceTo(vec))
                            targetPos = vec;
                    }

                    if (Minecraft.getInstance().gameMode != null && hit instanceof BlockHitResult blockHit) {
                        if (blockEntity.getLevel().getBlockEntity(blockHit.getBlockPos()) instanceof AbstractElectricalConnector connector) {
                            if (connector.isConnected(blockEntity.getBlockPos()))
                                return;

                            targetPos = SableCompanion.INSTANCE.projectOutOfSubLevel(level, (Position) BlockPosUtil.toVec3(connector.getBlockPos()).add(connector.getConnectionPoint()));
                        }
                    }

                    var maxDistance = CNAConfig.getServer().maxWireLength.get() * 2;

                    renderWire(wireItem.getWireType(), poseStack, buffer, blockEntity, targetPos, maxDistance);
                }
            }
        }
    }

    @Override
    public boolean shouldRenderOffScreen(AbstractElectricalConnector blockEntity) {
        return true;
    }

    @Override
    public AABB getRenderBoundingBox(AbstractElectricalConnector blockEntity) {
        return AABB.INFINITE;
    }
}