package net.bedrockgraves.overlay;

import com.mojang.datafixers.util.Pair;
import eu.pb4.graves.config.ConfigManager;
import eu.pb4.graves.grave.Grave;
import eu.pb4.graves.grave.GraveManager;
import eu.pb4.graves.registry.GraveBlockEntity;
import eu.pb4.graves.ui.GraveGui;
import eu.pb4.polymer.virtualentity.api.data.EntityData;
import eu.pb4.sgui.api.GuiHelpers;
import net.bedrockgraves.BedrockGravesAddon;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class BedrockOverlayManager {
    // Entity ID counter - uses negative range to avoid conflicts with real entities
    private static final AtomicInteger ENTITY_IDS = new AtomicInteger(-2_000_000_000);

    // Active overlay tracking: player UUID -> (grave position -> overlay info)
    private static final Map<UUID, Map<BlockPos, HoloInfo>> ACTIVE = new ConcurrentHashMap<>();

    // Timing constants
    private static final int UPDATE_PERIOD_TICKS = 5;           // Update every 0.25 seconds
    private static final long HIDE_DELAY_MS = 200;              // Delay before hiding overlay
    private static final long RESPAWN_INTERVAL_MS = 30_000;     // Respawn entities every 30s

    // Distance constants
    private static final double MAX_DISTANCE_SQ = 48 * 48;      // Max render distance (48 blocks)
    private static final double GRACE_DISTANCE_SQ = 2 * 2;      // Skip LOS check within 2 blocks

    // Position offsets for visual elements
    private static final double OFFSET_Y = 1.0;                 // Lift text above grave block
    private static final double TEXT_Y_EPSILON = 0.01;          // Micro-lift to avoid physics ejection
    private static final double FOX_OFFSET_X = 0.45;
    private static final double FOX_OFFSET_Z = 0.0;
    private static final double FOX_OFFSET_Y = -0.14;           // Sink item closer to ground

    // Floodgate API state (lazy-loaded via reflection)
    private static boolean floodgateChecked = false;
    private static Method floodgateCheckMethod;
    private static Object floodgateApiInstance;

    private record HoloInfo(int textEntityId, int itemFoxId, Identifier worldId, long lastSpawnMs, long lastSeenMs, long lastHeadSendMs) {
    }

    private BedrockOverlayManager() {
    }

    public static void clearPlayer(UUID uuid) {
        ACTIVE.remove(uuid);
    }

    public static void tick(GraveBlockEntity be) {
        if (be.getLevel() == null || be.getLevel().isClientSide()) {
            return;
        }
        if (!(be.getLevel() instanceof ServerLevel world)) {
            return;
        }
        BlockPos pos = be.getBlockPos();
        Grave grave = be.getGrave();

        if (grave == null || grave.isRemoved()) {
            clearForWorld(world, pos);
            return;
        }

        if (world.getGameTime() % UPDATE_PERIOD_TICKS != 0) {
            return;
        }

        Identifier worldId = world.dimension().identifier();
        boolean hasBedrockViewer = false;
        ItemStack displayItem = pickItem(grave);

        for (ServerPlayer player : world.players()) {
            boolean isBedrock = isFloodgateBedrock(player);
            Map<BlockPos, HoloInfo> byPos = ACTIVE.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>());
            HoloInfo existing = byPos.get(pos);

            double distSq = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            boolean inRange = distSq <= MAX_DISTANCE_SQ;
            boolean hasSight = !isBedrock || distSq <= GRACE_DISTANCE_SQ || hasLineOfSight(player, pos)
                    || isViewingGraveUi(player);
            long now = System.currentTimeMillis();

            if (!isBedrock || !inRange || !hasSight) {
                if (existing != null && now - existing.lastSeenMs() > HIDE_DELAY_MS) {
                    destroy(player, existing.textEntityId());
                    if (existing.itemFoxId() != -1) {
                        destroy(player, existing.itemFoxId());
                    }
                    byPos.remove(pos);
                }
                continue;
            }

            hasBedrockViewer = true;
            Component text = buildText(world, grave, pos);
            boolean needHead = existing == null || now - existing.lastHeadSendMs() > 1000;
            if (needHead) {
                sendHead(player, world, pos);
            }

            if (existing == null) {
                int textId = spawnText(player, pos, text);
                int itemFoxId = spawnFox(player, world, pos, displayItem);
                byPos.put(pos, new HoloInfo(textId, itemFoxId, worldId, now, now, now));
            } else {
                boolean needsRespawn = now - existing.lastSpawnMs() > RESPAWN_INTERVAL_MS;
                int textId = existing.textEntityId();
                int itemFoxId = existing.itemFoxId();
                if (needsRespawn) {
                    destroy(player, textId);
                    if (itemFoxId != -1) {
                        destroy(player, itemFoxId);
                    }
                    textId = spawnText(player, pos, text);
                    itemFoxId = spawnFox(player, world, pos, displayItem);
                } else {
                    updateText(player, textId, text);
                    if (itemFoxId == -1) {
                        itemFoxId = spawnFox(player, world, pos, displayItem);
                    } else {
                        sendEquipAndHide(player, itemFoxId, displayItem);
                    }
                }
                byPos.put(pos, new HoloInfo(textId, itemFoxId, worldId, needsRespawn ? now : existing.lastSpawnMs(), now, needHead ? now : existing.lastHeadSendMs()));
            }
        }

        if (!hasBedrockViewer) {
            sendRealBlockStateToAll(world, pos);
        }
    }

    public static void clearForWorld(ServerLevel world, BlockPos pos) {
        Identifier worldId = world.dimension().identifier();
        for (ServerPlayer player : world.players()) {
            Map<BlockPos, HoloInfo> byPos = ACTIVE.get(player.getUUID());
            if (byPos == null) {
                continue;
            }
            HoloInfo info = byPos.get(pos);
            if (info != null && info.worldId().equals(worldId)) {
                destroy(player, info.textEntityId());
                if (info.itemFoxId() != -1) {
                    destroy(player, info.itemFoxId());
                }
                byPos.remove(pos);
            }
        }
    }

    private static boolean isViewingGraveUi(ServerPlayer player) {
        try {
            return GuiHelpers.getCurrentGui(player) instanceof GraveGui;
        } catch (Exception e) {
            BedrockGravesAddon.LOGGER.debug("[UniversalGraves-Geyser] Failed to check grave UI state for {}: {}",
                    player.getName().getString(), e.getMessage());
            return false;
        }
    }

    private static void sendHead(ServerPlayer player, ServerLevel world, BlockPos pos) {
        BlockState headState = Blocks.SKELETON_SKULL.defaultBlockState();
        if (headState.hasProperty(BlockStateProperties.ROTATION_16)) {
            headState = headState.setValue(BlockStateProperties.ROTATION_16, world.getRandom().nextInt(16));
        }
        player.connection.send(new ClientboundBlockUpdatePacket(pos, headState));

        SkullBlockEntity skull = new SkullBlockEntity(pos, headState);
        skull.setLevel(world);
        var update = skull.getUpdatePacket();
        if (update != null) {
            player.connection.send(update);
        }
    }

    private static void sendRealBlockState(ServerPlayer player, ServerLevel world, BlockPos pos) {
        BlockState realState = world.getBlockState(pos);
        player.connection.send(new ClientboundBlockUpdatePacket(pos, realState));
        var be = world.getBlockEntity(pos);
        if (be != null) {
            var update = be.getUpdatePacket();
            if (update != null) {
                player.connection.send(update);
            }
        }
    }

    private static void destroy(ServerPlayer player, int entityId) {
        player.connection.send(new ClientboundRemoveEntitiesPacket(entityId));
    }

    private static void sendRealBlockStateToAll(ServerLevel world, BlockPos pos) {
        for (ServerPlayer player : world.players()) {
            if (isFloodgateBedrock(player)) {
                sendRealBlockState(player, world, pos);
            }
        }
    }

    /**
     * Metadata for the invisible, marker, name-visible armor stand that carries the grave text.
     * Built by hand (instead of constructing a server-side ArmorStand) so it does not depend on
     * armor stand method names, only on the public data accessors.
     */
    private static List<SynchedEntityData.DataValue<?>> textMetadata(Component name) {
        List<SynchedEntityData.DataValue<?>> data = new ArrayList<>();
        data.add(SynchedEntityData.DataValue.create(EntityData.FLAGS, (byte) (1 << EntityData.INVISIBLE_FLAG_INDEX)));
        data.add(SynchedEntityData.DataValue.create(EntityData.NO_GRAVITY, true));
        data.add(SynchedEntityData.DataValue.create(EntityData.CUSTOM_NAME, Optional.of(name)));
        data.add(SynchedEntityData.DataValue.create(EntityData.NAME_VISIBLE, true));
        data.add(SynchedEntityData.DataValue.create(ArmorStand.DATA_CLIENT_FLAGS, (byte) ArmorStand.CLIENT_FLAG_MARKER));
        return data;
    }

    private static int spawnText(ServerPlayer player, BlockPos pos, Component name) {
        int entityId = ENTITY_IDS.getAndDecrement();
        player.connection.send(new ClientboundAddEntityPacket(
                entityId,
                UUID.randomUUID(),
                pos.getX() + 0.5,
                pos.getY() + OFFSET_Y + TEXT_Y_EPSILON,
                pos.getZ() + 0.5,
                0.0f,
                0.0f,
                EntityTypes.ARMOR_STAND,
                0,
                Vec3.ZERO,
                0.0));
        player.connection.send(new ClientboundSetEntityDataPacket(entityId, textMetadata(name)));
        return entityId;
    }

    private static void updateText(ServerPlayer player, int entityId, Component name) {
        player.connection.send(new ClientboundSetEntityDataPacket(entityId, textMetadata(name)));
    }

    private static int spawnFox(ServerPlayer player, ServerLevel world, BlockPos pos, ItemStack stack) {
        int entityId = ENTITY_IDS.getAndDecrement();
        player.connection.send(new ClientboundAddEntityPacket(
                entityId,
                UUID.randomUUID(),
                pos.getX() + 0.5 + FOX_OFFSET_X,
                pos.getY() + FOX_OFFSET_Y,
                pos.getZ() + 0.5 + FOX_OFFSET_Z,
                0.0f,
                0.0f,
                EntityTypes.FOX,
                0,
                Vec3.ZERO,
                0.0));
        sendFoxMeta(player, world, entityId);

        scheduleDelayedEquip(player, entityId, stack, 2);
        return entityId;
    }

    private static void scheduleDelayedEquip(ServerPlayer player, int entityId, ItemStack stack, int ticksDelay) {
        if (ticksDelay <= 0) {
            sendEquipAndHide(player, entityId, stack);
            return;
        }
        BedrockGravesAddon.DO_ON_NEXT_TICK.add(new Runnable() {
            int remaining = ticksDelay;

            @Override
            public void run() {
                if (--remaining <= 0) {
                    sendEquipAndHide(player, entityId, stack);
                } else {
                    BedrockGravesAddon.DO_ON_NEXT_TICK.add(this);
                }
            }
        });
    }

    private static void sendEquipAndHide(ServerPlayer player, int entityId, ItemStack stack) {
        try {
            ItemStack send = stack.isEmpty() ? new ItemStack(Items.COMPASS) : stack.copyWithCount(1);
            player.connection.send(new ClientboundSetEquipmentPacket(entityId,
                    List.of(new Pair<>(EquipmentSlot.MAINHAND, send))));

            MobEffectInstance invis = new MobEffectInstance(MobEffects.INVISIBILITY, Integer.MAX_VALUE, 0, true, false, false);
            player.connection.send(new ClientboundUpdateMobEffectPacket(entityId, invis, true));
        } catch (Exception e) {
            BedrockGravesAddon.LOGGER.debug("Failed to equip/hide fox: {}", e.toString());
        }
    }

    /**
     * Sends the fox's entity metadata (invisible, no gravity, silent, baby, sitting).
     * A throwaway server-side fox is created only to read its metadata; the fox-specific
     * setters are called by name so a rename in a future Minecraft version degrades the
     * visual instead of breaking the whole mod.
     */
    private static void sendFoxMeta(ServerPlayer player, ServerLevel world, int entityId) {
        List<SynchedEntityData.DataValue<?>> entries = null;
        try {
            Entity fox = EntityTypes.FOX.create(world, EntitySpawnReason.COMMAND);
            if (fox != null) {
                fox.setNoGravity(true);
                fox.setSilent(true);
                fox.setInvisible(true);
                invokeBoolean(fox, "setBaby", true);
                invokeBoolean(fox, "setSitting", true);
                invokeBoolean(fox, "setNoAi", true);
                entries = fox.getEntityData().packDirty();
            }
        } catch (Throwable t) {
            BedrockGravesAddon.LOGGER.debug("[UniversalGraves-Geyser] Failed to build fox metadata: {}", t.toString());
        }

        if (entries == null || entries.isEmpty()) {
            // Fallback: at least keep the fox invisible and weightless
            entries = new ArrayList<>();
            entries.add(SynchedEntityData.DataValue.create(EntityData.FLAGS, (byte) (1 << EntityData.INVISIBLE_FLAG_INDEX)));
            entries.add(SynchedEntityData.DataValue.create(EntityData.NO_GRAVITY, true));
            entries.add(SynchedEntityData.DataValue.create(EntityData.SILENT, true));
        }
        player.connection.send(new ClientboundSetEntityDataPacket(entityId, entries));
    }

    /** Calls a public/protected/private boolean setter by name anywhere in the class hierarchy. */
    private static void invokeBoolean(Object target, String name, boolean value) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == boolean.class) {
                    try {
                        m.setAccessible(true);
                        m.invoke(target, value);
                        return;
                    } catch (Exception e) {
                        BedrockGravesAddon.LOGGER.debug("[UniversalGraves-Geyser] {} failed: {}", name, e.toString());
                        return;
                    }
                }
            }
        }
        BedrockGravesAddon.LOGGER.debug("[UniversalGraves-Geyser] Method {} not found on {}", name, target.getClass().getName());
    }

    private static Component buildText(ServerLevel world, Grave grave, BlockPos pos) {
        Map<String, Component> placeholders = world.getServer() != null ? grave.getPlaceholders(world.getServer())
                : Map.of();
        String playerName = placeholders.getOrDefault("player", Component.literal("<Unknown>")).getString();
        String death = placeholders.getOrDefault("death_cause", Component.empty()).getString();

        int itemCount = 0;
        for (var stack : grave.getItems()) {
            if (!stack.isEmpty()) {
                itemCount++;
            }
        }

        var config = ConfigManager.getConfig();
        long protectionTime = GraveManager.INSTANCE.getProtectionTime();
        long breakingTime = GraveManager.INSTANCE.getBreakingTime();

        String protection = protectionTime > -1
                ? config.getFormattedTime(
                        Math.max(0, grave.getTimeLeft((int) protectionTime, config.protection.useRealTime)))
                : config.texts.infinityText;
        String breaking = breakingTime > -1
                ? config.getFormattedTime(
                        Math.max(0, grave.getTimeLeft((int) breakingTime, config.protection.useRealTime)))
                : config.texts.infinityText;

        Component line1 = Component.literal("Grave of " + playerName).withStyle(ChatFormatting.YELLOW);
        Component line1b = Component.literal(death).withStyle(ChatFormatting.YELLOW);
        Component line2 = Component.literal("Items: " + itemCount + "  XP: " + grave.getXp()).withStyle(ChatFormatting.WHITE);
        Component line3 = Component.literal("Protected for: ").withStyle(ChatFormatting.BLUE)
                .append(Component.literal(protection).withStyle(ChatFormatting.WHITE));
        Component line4 = Component.literal("Breaks in: ").withStyle(ChatFormatting.RED)
                .append(Component.literal(breaking).withStyle(ChatFormatting.WHITE));

        return Component.empty()
                .append(line1)
                .append(Component.literal("\n"))
                .append(line1b)
                .append(Component.literal("\n"))
                .append(line2)
                .append(Component.literal("\n"))
                .append(line3)
                .append(Component.literal("\n"))
                .append(line4);
    }

    private static boolean hasLineOfSight(ServerPlayer player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        Vec3 targetBase = Vec3.atCenterOf(pos).add(0, OFFSET_Y, 0);
        Vec3[] targets = new Vec3[] {
                targetBase,
                targetBase.add(0.4, 0, 0.4),
                targetBase.add(-0.4, 0, -0.4),
                targetBase.add(0, 0.5, 0),
                targetBase.add(0, -0.3, 0)
        };

        for (Vec3 target : targets) {
            ClipContext ctx = new ClipContext(
                    eye,
                    target,
                    ClipContext.Block.VISUAL,
                    ClipContext.Fluid.NONE,
                    player);
            if (player.level().clip(ctx).getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFloodgateBedrock(ServerPlayer player) {
        if (!floodgateChecked) {
            floodgateChecked = true;
            try {
                Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Method getInstance = apiClass.getMethod("getInstance");
                floodgateApiInstance = getInstance.invoke(null);
                floodgateCheckMethod = apiClass.getMethod("isFloodgatePlayer", UUID.class);
                BedrockGravesAddon.LOGGER.info("[UniversalGraves-Geyser] Floodgate detected, enabling Bedrock overlays");
            } catch (Exception e) {
                BedrockGravesAddon.LOGGER.info("[UniversalGraves-Geyser] Floodgate not detected, Bedrock overlays disabled ({})",
                        e.toString());
            }
        }

        if (floodgateApiInstance == null || floodgateCheckMethod == null) {
            return false;
        }

        try {
            return (boolean) floodgateCheckMethod.invoke(floodgateApiInstance, player.getUUID());
        } catch (Exception e) {
            BedrockGravesAddon.LOGGER.debug("[UniversalGraves-Geyser] Failed to query Floodgate: {}", e.toString());
            return false;
        }
    }

    private static ItemStack pickItem(Grave grave) {
        for (var p : grave.getItems()) {
            if (!p.isEmpty() && !p.stack().isEmpty()) {
                return p.stack().copy();
            }
        }
        return ItemStack.EMPTY;
    }
}
