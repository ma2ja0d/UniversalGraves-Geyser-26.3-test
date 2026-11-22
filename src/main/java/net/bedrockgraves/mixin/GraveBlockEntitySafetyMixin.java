package net.bedrockgraves.mixin;

import eu.pb4.graves.grave.Grave;
import eu.pb4.graves.registry.GraveBlockEntity;
import net.bedrockgraves.BedrockGravesAddon;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Prevents graves from self-destructing during slow server load when grave data
 * hasn't been fully initialized yet. Allows legitimate destruction once the grave
 * is properly loaded and marked as removed.
 */
@Mixin(GraveBlockEntity.class)
public class GraveBlockEntitySafetyMixin {
    @Redirect(
            method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z", ordinal = 0))
    private static boolean bedrockgraves$skipSelfDestruct(Level level, BlockPos pos, BlockState state) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof GraveBlockEntity gbe)) {
            // Not a grave block entity, allow the state change
            return level.setBlockAndUpdate(pos, state);
        }

        Grave grave = gbe.getGrave();
        if (grave == null) {
            // Grave data not loaded yet - skip destruction to prevent item loss
            BedrockGravesAddon.LOGGER.debug("[UniversalGraves-Geyser] Skipping grave destruction at {} - data not loaded", pos);
            return false;
        }

        if (grave.isRemoved()) {
            // Grave is legitimately removed (player collected items), allow destruction
            return level.setBlockAndUpdate(pos, state);
        }

        // Grave exists but isn't marked as removed - skip destruction
        BedrockGravesAddon.LOGGER.debug("[UniversalGraves-Geyser] Skipping grave destruction at {} - grave not marked removed", pos);
        return false;
    }
}
