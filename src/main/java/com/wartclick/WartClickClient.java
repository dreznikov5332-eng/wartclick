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
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class WartClickClient implements ClientModInitializer {
    /** Сколько блоков проверять вперёд по ходу движения. */
    private static final int FORWARD = 4;
    /** Сколько блоков проверять назад. */
    private static final int BACK = 0;
    /** Сколько блоков проверять в каждую сторону (влево и вправо). */
    private static final int SIDE = 2;
    /** Сколько блоков проверять вверх. */
    private static final int VERT_UP = 5;
    /** Сколько блоков проверять вниз. */
    private static final int VERT_DOWN = 1;
    /** Максимум кликов за один тик. */
    private static final int MAX_CLICKS_PER_TICK = 10;
    /** Через сколько тиков можно снова кликнуть по тому же блоку. */
    private static final int SAME_POS_COOLDOWN = 3;
    /** Не бить по блокам, закрытым стеной (true) или бить всё в радиусе (false). */
    private static final boolean REQUIRE_LINE_OF_SIGHT = true;
    /** Показывать над хотбаром статистику за последнюю секунду. */
    private static final boolean DEBUG = true;

    /** Точки внутри блока, которые проверяются на видимость (сначала центр, потом края). */
    private static final double[] GRID = {0.5, 0.05, 0.95};
    /** Допуск: если луч упёрся в блок совсем рядом с целью (задевает край), блок считается видимым. */
    private static final double GRAZE = 1.6;

    private static KeyBinding toggleKey;

    private boolean enabled = false;
    private int tickCounter = 0;
    private Direction forwardDir = null;
    private final Map<BlockPos, Integer> recent = new HashMap<>();

    private final Set<BlockPos> dbgSeen = new HashSet<>();
    private final Set<BlockPos> dbgFar = new HashSet<>();
    private final Set<BlockPos> dbgBlocked = new HashSet<>();
    private final Set<BlockPos> dbgClicked = new HashSet<>();
    private boolean dbgNoHoe = false;
    private int dbgAllWart = 0;

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

        if (DEBUG && tickCounter % 20 == 0) {
            String msg = dbgNoHoe
                    ? "WartClick: в руке НЕ мотыга"
                    : "WartClick | в зоне нароста: " + dbgAllWart
                    + " | зрелых: " + dbgSeen.size()
                    + " | далеко: " + dbgFar.size()
                    + " | закрыто: " + dbgBlocked.size()
                    + " | клик: " + dbgClicked.size();
            player.sendMessage(Text.literal(msg), true);
            dbgSeen.clear();
            dbgFar.clear();
            dbgBlocked.clear();
            dbgClicked.clear();
            dbgAllWart = 0;
            dbgNoHoe = false;
        }

        ItemStack held = player.getMainHandStack();
        if (!held.isIn(ItemTags.HOES)) {
            dbgNoHoe = true;
            return;
        }

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
                for (int dy = VERT_UP; dy >= -VERT_DOWN; dy--) {
                    if (clicks >= MAX_CLICKS_PER_TICK) break scan;

                    BlockPos pos = center.add(fx * f + rx * s, dy, fz * f + rz * s);
                    BlockState state = client.world.getBlockState(pos);
                    if (!state.isOf(Blocks.NETHER_WART)) continue;
                    dbgAllWart++;
                    if (state.get(NetherWartBlock.AGE) < 3) continue;
                    dbgSeen.add(pos);
                    if (distToBlockSq(eye, pos) > reachSq) {
                        dbgFar.add(pos);
                        continue;
                    }
                    if (recent.containsKey(pos)) continue;

                    Vec3d hitVec = REQUIRE_LINE_OF_SIGHT
                            ? findVisiblePoint(client, player, eye, pos, reachSq)
                            : Vec3d.ofCenter(pos);
                    if (hitVec == null) {
                        dbgBlocked.add(pos);
                        continue;
                    }

                    Vec3d toEye = eye.subtract(hitVec);
                    Direction face = Direction.getFacing(toEye.x, toEye.y, toEye.z);
                    BlockHitResult hit = new BlockHitResult(hitVec, face, pos, false);
                    ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
                    if (result.isAccepted()) {
                        player.swingHand(Hand.MAIN_HAND);
                    }
                    recent.put(pos, tickCounter);
                    dbgClicked.add(pos);
                    clicks++;
                }
            }
        }
    }

    /** Квадрат расстояния от глаз до ближайшей точки блока. */
    private static double distToBlockSq(Vec3d eye, BlockPos pos) {
        double dx = Math.max(Math.max(pos.getX() - eye.x, 0), eye.x - (pos.getX() + 1));
        double dy = Math.max(Math.max(pos.getY() - eye.y, 0), eye.y - (pos.getY() + 1));
        double dz = Math.max(Math.max(pos.getZ() - eye.z, 0), eye.z - (pos.getZ() + 1));
        return dx * dx + dy * dy + dz * dz;
    }

    /** Точка на блоке в пределах досягаемости, видимая из глаз (или задетая по краю), либо null. */
    private Vec3d findVisiblePoint(MinecraftClient client, ClientPlayerEntity player,
                                   Vec3d eye, BlockPos pos, double reachSq) {
        for (double gx : GRID) {
            for (double gy : GRID) {
                for (double gz : GRID) {
                    Vec3d target = new Vec3d(pos.getX() + gx, pos.getY() + gy, pos.getZ() + gz);
                    if (eye.squaredDistanceTo(target) > reachSq) continue;
                    HitResult r = client.world.raycast(new RaycastContext(
                            eye, target,
                            RaycastContext.ShapeType.COLLIDER,
                            RaycastContext.FluidHandling.NONE,
                            player));
                    if (r.getType() == HitResult.Type.MISS
                            || r.getPos().squaredDistanceTo(target) < GRAZE * GRAZE) {
                        return target;
                    }
                }
            }
        }
        return null;
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
