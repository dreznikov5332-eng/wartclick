package com.wartclick;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

public class WartClickClient implements ClientModInitializer {
    /** Сколько блоков проверять вперёд по ходу движения. */
    private static final int FORWARD = 4;
    /** Сколько блоков проверять назад. */
    private static final int BACK = 0;
    /** Сколько блоков проверять в каждую сторону (влево и вправо). */
    private static final int SIDE = 2;
    /** Сколько блоков проверять вверх и вниз. */
    private static final int VERT_UP = 4;
    private static final int VERT_DOWN = 1;
    private static final int MAX_CLICKS_PER_TICK = 10;
    /** Через сколько тиков можно снова кликнуть по тому же блоку. */
    private static final int SAME_POS_COOLDOWN = 3;

    private static KeyBinding toggleKey;

    private boolean enabled = false;
    private int tickCounter = 0;
    private Direction forwardDir = null;
    private final Map<BlockPos, Integer> recent = new HashMap<>();

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.wartclick.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;

        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            recent.clear();
            forwardDir = null;
            if (player != null) {
                player.sendMessage(Text.literal("WartClick: " + (enabled ? "ВКЛ" : "ВЫКЛ")), true);
            }
        }

        if (!enabled) return;
        if (player == null || client.world == null || client.interactionManager == null) return;
        if (client.currentScreen != null) return;

        tickCounter++;
        recent.entrySet().removeIf(e -> tickCounter - e.getValue() > SAME_POS_COOLDOWN);

        ItemStack held = player.getMainHandStack();
        if (!held.isIn(ItemTags.HOES)) return;

        updateForward(player);

        Direction fwd = forwardDir;
        Direction right = fwd.rotateYClockwise();
        int fx = fwd.getOffsetX(), fz = fwd.getOffsetZ();
        int rx = right.getOffsetX(), rz = right.getOffsetZ();

        Vec3d eye = player.getEyePos();
        double reach = player.getBlockInteractionRange();
        double reachSq = reach * reach;
        BlockPos center = player.getBlockPos();
        int clicks = 0;

        scan:
        for (int f = FORWARD; f >= -BACK; f--) {
            for (int s = -SIDE; s <= SIDE; s++) {
                for (int dy = -VERT_DOWN; dy <= VERT_UP; dy++) {
                    if (clicks >= MAX_CLICKS_PER_TICK) break scan;

                    BlockPos pos = center.add(fx * f + rx * s, dy, fz * f + rz * s);
                    BlockState state = client.world.getBlockState(pos);
                    if (!state.isOf(Blocks.NETHER_WART)) continue;
                    if (state.get(NetherWartBlock.AGE) < 3) continue;
                    if (eye.squaredDistanceTo(Vec3d.ofCenter(pos)) > reachSq) continue;
                    if (recent.containsKey(pos)) continue;

                    BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
                    ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
                    if (result.isAccepted()) {
                        player.swingHand(Hand.MAIN_HAND);
                    }
                    recent.put(pos, tickCounter);
                    clicks++;
                }
            }
        }
    }

    /** Направление движения по скорости вагонетки; если стоим, остаётся прежнее. */
    private void updateForward(ClientPlayerEntity player) {
        Entity source = player.hasVehicle() ? player.getVehicle() : player;
        Vec3d v = source.getVelocity();
        if (v.x * v.x + v.z * v.z > 1.0E-4) {
            forwardDir = Math.abs(v.x) > Math.abs(v.z)
                    ? (v.x > 0 ? Direction.EAST : Direction.WEST)
                    : (v.z > 0 ? Direction.SOUTH : Direction.NORTH);
        } else if (forwardDir == null) {
            forwardDir = player.getHorizontalFacing();
        }
    }
}
