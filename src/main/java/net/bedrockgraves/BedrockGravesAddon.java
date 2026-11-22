package net.bedrockgraves;

import net.bedrockgraves.overlay.BedrockOverlayManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BedrockGravesAddon implements ModInitializer {
    public static final String MOD_ID = "universalgraves-geyser";
    public static final Logger LOGGER = LoggerFactory.getLogger("UniversalGravesGeyser");
    public static final Queue<Runnable> DO_ON_NEXT_TICK = new ConcurrentLinkedQueue<>();

    @Override
    public void onInitialize() {
        LOGGER.info("[UniversalGraves-Geyser] Initializing UniversalGraves-Geyser");

        // run queued tasks on server tick (drain the thread-safe queue)
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Runnable task;
            List<Runnable> tasks = new ArrayList<>();
            while ((task = DO_ON_NEXT_TICK.poll()) != null) {
                tasks.add(task);
            }
            for (var t : tasks) {
                try {
                    t.run();
                } catch (Throwable ex) {
                    LOGGER.error("Error executing delayed task", ex);
                }
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                BedrockOverlayManager.clearPlayer(handler.getPlayer().getUUID()));
    }
}
