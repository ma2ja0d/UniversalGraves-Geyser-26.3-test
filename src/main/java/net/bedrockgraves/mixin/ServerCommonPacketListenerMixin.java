package net.bedrockgraves.mixin;

import net.bedrockgraves.BedrockGravesAddon;
import net.bedrockgraves.gui.BedrockGuiTranslator;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * Intercepts inventory/GUI packets sent to Bedrock players and translates
 * custom player head items (used by Polymer/SGUI) to recognizable vanilla items.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public class ServerCommonPacketListenerMixin {

    @ModifyVariable(
            method = "send(Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    private Packet<?> bedrockgraves$translatePacket(Packet<?> packet) {
        if (!(packet instanceof ClientboundContainerSetSlotPacket)
                && !(packet instanceof ClientboundContainerSetContentPacket)) {
            return packet;
        }

        if (!(((Object) this) instanceof ServerGamePacketListenerImpl handler)) {
            return packet;
        }
        ServerPlayer player = handler.getPlayer();
        if (player == null || !BedrockGuiTranslator.isBedrockPlayer(player.getUUID())) {
            return packet;
        }

        try {
            if (packet instanceof ClientboundContainerSetSlotPacket slotPacket) {
                ItemStack original = slotPacket.getItem();
                ItemStack translated = BedrockGuiTranslator.translateForBedrock(original);
                if (translated != original) {
                    return new ClientboundContainerSetSlotPacket(
                            slotPacket.getContainerId(),
                            slotPacket.getStateId(),
                            slotPacket.getSlot(),
                            translated
                    );
                }
            } else if (packet instanceof ClientboundContainerSetContentPacket invPacket) {
                List<ItemStack> originalStacks = invPacket.items();
                List<ItemStack> translatedStacks = new ArrayList<>(originalStacks.size());
                boolean anyChanged = false;

                for (ItemStack stack : originalStacks) {
                    ItemStack translated = BedrockGuiTranslator.translateForBedrock(stack);
                    translatedStacks.add(translated);
                    if (translated != stack) {
                        anyChanged = true;
                    }
                }

                if (anyChanged) {
                    return new ClientboundContainerSetContentPacket(
                            invPacket.containerId(),
                            invPacket.stateId(),
                            translatedStacks,
                            BedrockGuiTranslator.translateForBedrock(invPacket.carriedItem())
                    );
                }
            }
        } catch (Exception e) {
            BedrockGravesAddon.LOGGER.debug("[UniversalGraves-Geyser] Failed to translate packet: {}", e.getMessage());
        }

        return packet;
    }
}
