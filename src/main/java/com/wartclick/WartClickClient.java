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
    /** Сколько блоков вокруг игрока сканировать. */
    private static final int SCAN_RADIUS = 3;
    /** Максимум кликов за один тик. */
    private static final int MAX_CLICKS_PER_TICK = 10;
    /** Через сколько тиков можно снова кликнуть по тому же блоку. */
    private static final int SAME_POS_COOLDOWN = 2;

    private static KeyBinding toggleKey;

    private boolean enabled = false;
    private int tickCounter = 0;
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

        Vec3d eye = player.getEyePos();
        double reach = player.getBlockInteractionRange() - 0.3;
        double reachSq = reach * reach;
        BlockPos center = player.getBlockPos();
        int clicks = 0;

        for (BlockPos p : BlockPos.iterate(
                center.add(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS),
                center.add(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS))) {
            if (clicks >= MAX_CLICKS_PER_TICK) break;

            BlockState state = client.world.getBlockState(p);
            if (!state.isOf(Blocks.NETHER_WART)) continue;
            if (state.get(NetherWartBlock.AGE) < 3) continue;
            if (eye.squaredDistanceTo(Vec3d.ofCenter(p)) > reachSq) continue;
            if (recent.containsKey(p)) continue;

            BlockPos pos = p.toImmutable();
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
