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
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

public class WartClickClient implements ClientModInitializer {
    private static final int GLOBAL_COOLDOWN = 4;
    private static final int SAME_POS_COOLDOWN = 10;

    private static KeyBinding toggleKey;

    private boolean enabled = false;
    private int cooldown = 0;
    private BlockPos lastPos = null;
    private int ticksSinceLast = Integer.MAX_VALUE;

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
            cooldown = 0;
            lastPos = null;
            if (player != null) {
                player.sendMessage(Text.literal("WartClick: " + (enabled ? "ВКЛ" : "ВЫКЛ")), true);
            }
        }

        if (!enabled) return;
        if (player == null || client.world == null || client.interactionManager == null) return;
        if (client.currentScreen != null) return;

        if (ticksSinceLast < Integer.MAX_VALUE) ticksSinceLast++;
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        ItemStack held = player.getMainHandStack();
        if (!held.isIn(ItemTags.HOES)) return;

        HitResult hit = player.raycast(player.getBlockInteractionRange(), 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) return;

        BlockHitResult blockHit = (BlockHitResult) hit;
        BlockPos pos = blockHit.getBlockPos();
        BlockState state = client.world.getBlockState(pos);

        if (!state.isOf(Blocks.NETHER_WART)) return;
        if (state.get(NetherWartBlock.AGE) < 3) return;

        if (pos.equals(lastPos) && ticksSinceLast < SAME_POS_COOLDOWN) return;

        ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, blockHit);
        if (result.isAccepted()) {
            player.swingHand(Hand.MAIN_HAND);
        }

        lastPos = pos.toImmutable();
        ticksSinceLast = 0;
        cooldown = GLOBAL_COOLDOWN;
    }
}
