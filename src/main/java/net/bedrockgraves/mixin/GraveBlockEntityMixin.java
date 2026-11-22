package net.bedrockgraves.mixin;

import eu.pb4.graves.registry.GraveBlockEntity;
import net.bedrockgraves.overlay.BedrockOverlayManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GraveBlockEntity.class)
public abstract class GraveBlockEntityMixin extends BlockEntity {
    public GraveBlockEntityMixin(BlockPos pos, BlockState state) {
        super(null, pos, state);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private static <T extends BlockEntity> void bedrockgraves$tick(Level level, BlockPos pos, BlockState state, T t, CallbackInfo ci) {
        if (!level.isClientSide() && t instanceof GraveBlockEntity gbe) {
            BedrockOverlayManager.tick(gbe);
        }
    }

    // In 26.x this replaces the old "onBlockReplaced" hook
    @Inject(method = "preRemoveSideEffects", at = @At("HEAD"))
    private void bedrockgraves$onRemoved(BlockPos pos, BlockState oldState, CallbackInfo ci) {
        if (this.level instanceof ServerLevel serverLevel) {
            BedrockOverlayManager.clearForWorld(serverLevel, pos);
        }
    }
}
