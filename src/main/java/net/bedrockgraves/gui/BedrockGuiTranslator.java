package net.bedrockgraves.gui;

import eu.pb4.graves.registry.GravesRegistry;
import eu.pb4.graves.registry.IconItem;
import net.bedrockgraves.BedrockGravesAddon;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * Translates Polymer/SGUI custom head items to real Minecraft items
 * that Bedrock/Geyser players can see and understand.
 *
 * Universal Graves uses IconItem (custom player heads with textures) for these buttons:
 * - NEXT_PAGE / NEXT_PAGE_BLOCKED -> "Next Page"
 * - PREVIOUS_PAGE / PREVIOUS_PAGE_BLOCKED -> "Previous Page"
 * - QUICK_PICKUP -> "Take All Items"
 * - BREAK_GRAVE -> "Break Grave"
 * - REMOVE_PROTECTION -> "Remove Protection"
 *
 * Other buttons already use vanilla items (GOLD_INGOT, LEAD, ENDER_PEARL, etc.)
 */
public final class BedrockGuiTranslator {

    // Floodgate API state (lazy-loaded via reflection)
    private static boolean floodgateChecked = false;
    private static Method floodgateCheckMethod;
    private static Object floodgateApiInstance;

    private BedrockGuiTranslator() {
    }

    /**
     * Checks if the given UUID belongs to a Bedrock player via Floodgate.
     */
    public static boolean isBedrockPlayer(UUID uuid) {
        if (!floodgateChecked) {
            floodgateChecked = true;
            try {
                Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Method getInstance = apiClass.getMethod("getInstance");
                floodgateApiInstance = getInstance.invoke(null);
                floodgateCheckMethod = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            } catch (Exception e) {
                BedrockGravesAddon.LOGGER.debug("[UniversalGraves-Geyser] Floodgate not available for GUI translation: {}", e.getMessage());
            }
        }

        if (floodgateApiInstance == null || floodgateCheckMethod == null) {
            return false;
        }

        try {
            return (boolean) floodgateCheckMethod.invoke(floodgateApiInstance, uuid);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Translates a GUI item for Bedrock players.
     * Replaces Universal Graves IconItem and custom player heads with recognizable vanilla items.
     *
     * @param stack The original ItemStack
     * @return A translated ItemStack for Bedrock, or the original if no translation needed
     */
    public static ItemStack translateForBedrock(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }

        // Check if this is Universal Graves' IconItem (custom item that Polymer transforms to player heads)
        // This is the PRIMARY check - IconItem is what's actually in the packets before Polymer transforms them
        if (stack.is(GravesRegistry.ICON_ITEM)) {
            return translateIconItem(stack);
        }

        // Fallback: Also check for player heads (in case Polymer has already transformed the item)
        if (!stack.is(Items.PLAYER_HEAD)) {
            return stack;
        }

        // Get the custom name to determine what this button does
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        if (customName == null) {
            // No custom name - might be a decorative head, leave as-is
            return stack;
        }

        String name = customName.getString().toLowerCase();

        // Get lore for additional context and to preserve it
        ItemLore lore = stack.get(DataComponents.LORE);
        List<Component> loreLines = lore != null ? lore.lines() : List.of();

        // Translate based on Universal Graves button names
        ItemStack translated = translateUniversalGravesButton(name, customName, loreLines);
        if (translated != null) {
            return translated;
        }

        // Fallback: try generic button name matching
        translated = translateGenericButton(name, customName, loreLines);
        if (translated != null) {
            return translated;
        }

        // If we couldn't identify the button, replace with a generic item
        // to avoid Steve head confusion - preserve original name and lore
        return createItem(Items.PAPER.getDefaultInstance(), customName, loreLines);
    }

    /**
     * Translates Universal Graves IconItem based on its TEXTURE component.
     * This directly reads the texture type to determine what button it represents.
     */
    private static ItemStack translateIconItem(ItemStack stack) {
        // Get the texture component that tells us what this icon represents
        IconItem.Texture texture = stack.get(IconItem.TEXTURE);
        if (texture == null) {
            texture = IconItem.Texture.INVALID;
        }

        // Get existing name and lore to preserve them
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        ItemLore lore = stack.get(DataComponents.LORE);
        List<Component> loreLines = lore != null ? lore.lines() : List.of();

        // Translate based on the texture type
        return switch (texture) {
            case NEXT_PAGE -> createItem(Items.GREEN_STAINED_GLASS.getDefaultInstance(),
                    customName != null ? customName : Component.literal("Next Page →").withStyle(ChatFormatting.GREEN),
                    loreLines);
            case NEXT_PAGE_BLOCKED -> createItem(Items.GRAY_STAINED_GLASS.getDefaultInstance(),
                    customName != null ? customName : Component.literal("Next Page →").withStyle(ChatFormatting.DARK_GRAY),
                    loreLines);
            case PREVIOUS_PAGE -> createItem(Items.RED_STAINED_GLASS.getDefaultInstance(),
                    customName != null ? customName : Component.literal("← Previous Page").withStyle(ChatFormatting.RED),
                    loreLines);
            case PREVIOUS_PAGE_BLOCKED -> createItem(Items.GRAY_STAINED_GLASS.getDefaultInstance(),
                    customName != null ? customName : Component.literal("← Previous Page").withStyle(ChatFormatting.DARK_GRAY),
                    loreLines);
            case QUICK_PICKUP -> createItem(Items.CHEST.getDefaultInstance(),
                    customName != null ? customName : Component.literal("Take All Items").withStyle(ChatFormatting.GOLD),
                    loreLines);
            case BREAK_GRAVE -> createItem(Items.TNT.getDefaultInstance(),
                    customName != null ? customName : Component.literal("Break Grave").withStyle(ChatFormatting.RED),
                    loreLines);
            case REMOVE_PROTECTION -> createItem(Items.SHIELD.getDefaultInstance(),
                    customName != null ? customName : Component.literal("Remove Protection").withStyle(ChatFormatting.RED),
                    loreLines);
            case TATER -> createItem(Items.POTATO.getDefaultInstance(),
                    customName != null ? customName : Component.literal("Tater"),
                    loreLines);
            default -> createItem(Items.PAPER.getDefaultInstance(),
                    customName != null ? customName : Component.literal("Button"),
                    loreLines);
        };
    }

    /**
     * Translates Universal Graves specific buttons.
     * These are the exact button names from Universal Graves source code.
     */
    private static ItemStack translateUniversalGravesButton(String nameLower, Component originalName, List<Component> lore) {
        // === NAVIGATION BUTTONS ===
        // "Next Page" - IconItem.Texture.NEXT_PAGE / NEXT_PAGE_BLOCKED
        if (nameLower.contains("next page")) {
            boolean disabled = nameLower.startsWith("§8") || isDarkGray(originalName);
            if (disabled) {
                return createItem(Items.GRAY_STAINED_GLASS.getDefaultInstance(),
                        Component.literal("Next Page →").withStyle(ChatFormatting.DARK_GRAY), lore);
            }
            return createItem(Items.GREEN_STAINED_GLASS.getDefaultInstance(),
                    Component.literal("Next Page →").withStyle(ChatFormatting.GREEN), lore);
        }

        // "Previous Page" - IconItem.Texture.PREVIOUS_PAGE / PREVIOUS_PAGE_BLOCKED
        if (nameLower.contains("previous page")) {
            boolean disabled = nameLower.startsWith("§8") || isDarkGray(originalName);
            if (disabled) {
                return createItem(Items.GRAY_STAINED_GLASS.getDefaultInstance(),
                        Component.literal("← Previous Page").withStyle(ChatFormatting.DARK_GRAY), lore);
            }
            return createItem(Items.RED_STAINED_GLASS.getDefaultInstance(),
                    Component.literal("← Previous Page").withStyle(ChatFormatting.RED), lore);
        }

        // === ACTION BUTTONS ===
        // "Take All Items" - IconItem.Texture.QUICK_PICKUP
        if (nameLower.contains("take all") || nameLower.contains("quick pickup")) {
            return createItem(Items.CHEST.getDefaultInstance(),
                    Component.literal("Take All Items").withStyle(ChatFormatting.GOLD), lore);
        }

        // "Break Grave" - IconItem.Texture.BREAK_GRAVE
        if (nameLower.contains("break grave")) {
            return createItem(Items.TNT.getDefaultInstance(),
                    Component.literal("Break Grave").withStyle(ChatFormatting.RED), lore);
        }

        // "Remove Protection" - IconItem.Texture.REMOVE_PROTECTION
        if (nameLower.contains("remove protection")) {
            return createItem(Items.SHIELD.getDefaultInstance(),
                    Component.literal("Remove Protection").withStyle(ChatFormatting.RED), lore);
        }

        return null;
    }

    /**
     * Translates generic button names that might be used by other mods or custom configs.
     */
    private static ItemStack translateGenericButton(String nameLower, Component originalName, List<Component> lore) {
        // Navigation - generic
        if (nameLower.contains("next") || nameLower.contains("forward") || nameLower.contains(">>") || nameLower.contains("→")) {
            return createItem(Items.GREEN_STAINED_GLASS.getDefaultInstance(),
                    Component.literal("Next →").withStyle(ChatFormatting.GREEN), lore);
        }
        if (nameLower.contains("previous") || nameLower.contains("prev") || nameLower.contains("<<") || nameLower.contains("←")) {
            return createItem(Items.RED_STAINED_GLASS.getDefaultInstance(),
                    Component.literal("← Previous").withStyle(ChatFormatting.RED), lore);
        }
        if (nameLower.contains("back")) {
            return createItem(Items.ARROW.getDefaultInstance(),
                    Component.literal("← Back").withStyle(ChatFormatting.YELLOW), lore);
        }

        // Close/exit
        if (nameLower.contains("close") || nameLower.contains("exit") || nameLower.contains("cancel")) {
            return createItem(Items.BARRIER.getDefaultInstance(),
                    Component.literal("Close").withStyle(ChatFormatting.RED), lore);
        }

        // Confirm/accept
        if (nameLower.contains("confirm") || nameLower.contains("click again")) {
            return createItem(Items.LIME_DYE.getDefaultInstance(),
                    Component.literal("Confirm").withStyle(ChatFormatting.GREEN), lore);
        }

        // Collect/take
        if (nameLower.contains("collect") || nameLower.contains("take") || nameLower.contains("retrieve") || nameLower.contains("claim")) {
            return createItem(Items.CHEST.getDefaultInstance(),
                    Component.literal("Collect Items").withStyle(ChatFormatting.GOLD), lore);
        }

        // Delete/remove/destroy
        if (nameLower.contains("delete") || nameLower.contains("destroy")) {
            return createItem(Items.LAVA_BUCKET.getDefaultInstance(),
                    Component.literal("Delete").withStyle(ChatFormatting.DARK_RED), lore);
        }

        // Info/help
        if (nameLower.contains("info") || nameLower.contains("help") || nameLower.contains("?")) {
            return createItem(Items.BOOK.getDefaultInstance(),
                    Component.literal("Info").withStyle(ChatFormatting.AQUA), lore);
        }

        // Player grave representation (show as skeleton skull)
        if (nameLower.contains("grave") || nameLower.contains("death") || nameLower.contains("died")) {
            return createItem(Items.SKELETON_SKULL.getDefaultInstance(), originalName, lore);
        }

        // Protection related
        if (nameLower.contains("protect") || nameLower.contains("lock") || nameLower.contains("unlock")) {
            return createItem(Items.SHIELD.getDefaultInstance(), originalName, lore);
        }

        return null;
    }

    private static boolean isDarkGray(Component name) {
        var color = name.getStyle().getColor();
        return color != null && ChatFormatting.DARK_GRAY.getColor() != null
                && color.getValue() == ChatFormatting.DARK_GRAY.getColor();
    }

    /**
     * Creates an ItemStack with the given name and lore.
     */
    private static ItemStack createItem(ItemStack base, Component name, List<Component> lore) {
        ItemStack stack = base.copy();
        stack.set(DataComponents.CUSTOM_NAME, name);
        if (lore != null && !lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lore));
        }
        return stack;
    }

    /**
     * Creates an ItemStack with the given name (no lore).
     */
    private static ItemStack createItem(ItemStack base, Component name) {
        return createItem(base, name, List.of());
    }
}
