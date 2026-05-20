package org.clientharvest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.ToggleKeyMapping;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;

import org.lwjgl.glfw.GLFW;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Field;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class HarvestModClient implements ClientModInitializer {
    public static Minecraft client;
    public static boolean enabled = true;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/clientharvest.json");

    // Cached reflection handle for the package-private Inventory.selected field
    private static Field INVENTORY_SELECTED_FIELD = null;

    public static class Config {
        public boolean enabled = true;
    }

    public final ToggleKeyMapping.Category category =
            ToggleKeyMapping.Category.register(Identifier.fromNamespaceAndPath("clientharvest", "general"));

    @Override
    public void onInitializeClient() {
        client = Minecraft.getInstance();
        loadConfig();
        UseBlockCallback.EVENT.register(this::onBlockUse);

        KeyMapping toggleKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.clientsideharvest.toggle",
                        GLFW.GLFW_KEY_G,
                        category
                )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                enabled = !enabled;
                saveConfig();
                if (client.player != null) {
                    client.gui.setOverlayMessage(
                            Component.literal("[Client Side Harvest] " + (enabled ? "ON" : "OFF")),
                            false
                    );
                }
            }
        });
    }

    // -----------------------------------------------------------------------
    // Core event handler
    // -----------------------------------------------------------------------

    public InteractionResult onBlockUse(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
        if (!enabled) return InteractionResult.PASS;
        if (client.gameMode == null) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        BlockState state = level.getBlockState(hitResult.getBlockPos());
        if (!isMature(state)) return InteractionResult.PASS;

        boolean hasFortune = hasFortuneInMainHand(player, level);
        int hotbarSlot     = findMatchingSeedInHotbar(player, state);
        boolean inOffhand  = isSeedFor(player.getOffhandItem(), state);

        // --- Branch 1: matching seed exists somewhere accessible --------------
        if (hotbarSlot != -1 || inOffhand) {
            if (hasFortune) {
                // Ensure matching seed is in the offhand, then break + PASS
                // so the off-hand replant fires.
                if (!inOffhand) {
                    // hotbarSlot guaranteed != -1 here (inOffhand is false)
                    swapHotbarSlotToOffhand(hotbarSlot);
                }
                client.gameMode.startDestroyBlock(hitResult.getBlockPos(), hitResult.getDirection());
                return InteractionResult.PASS;  // lets off-hand seed replant
            } else {
                // No fortune: if matching seed is already in offhand and not in
                // main hand, use the off-hand replant path; otherwise switch the
                // hotbar to the matching seed slot.
                if (inOffhand && !isSeed(player.getMainHandItem())) {
                    client.gameMode.startDestroyBlock(hitResult.getBlockPos(), hitResult.getDirection());
                    return InteractionResult.PASS;
                }
                if (hotbarSlot != -1) {
                    switchHotbarSlot(hotbarSlot);
                    client.gameMode.startDestroyBlock(hitResult.getBlockPos(), hitResult.getDirection());
                    return InteractionResult.SUCCESS;
                }
            }
        }

        // --- Branch 2: no matching seed — fall back to any-seed logic ---------
        boolean mainHasSeed = isSeed(player.getMainHandItem());
        boolean offHasSeed  = isSeed(player.getOffhandItem());

        if (hasFortune) {
            if (offHasSeed) {
                // Fortune in main, any seed in offhand → break with fortune, replant
                client.gameMode.startDestroyBlock(hitResult.getBlockPos(), hitResult.getDirection());
                return InteractionResult.PASS;
            } else {
                // Fortune in main, no seed at all → just break, no replant
                client.gameMode.startDestroyBlock(hitResult.getBlockPos(), hitResult.getDirection());
                return InteractionResult.SUCCESS;
            }
        }

        // No fortune — original behaviour
        if (mainHasSeed) {
            client.gameMode.startDestroyBlock(hitResult.getBlockPos(), hitResult.getDirection());
            return InteractionResult.SUCCESS;
        } else if (offHasSeed) {
            client.gameMode.startDestroyBlock(hitResult.getBlockPos(), hitResult.getDirection());
            return InteractionResult.PASS;
        }

        return InteractionResult.PASS;
    }

    private static boolean isMature(BlockState state) {
        if (state.getBlock() instanceof CocoaBlock) {
            return state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE;
        } else if (state.getBlock() instanceof CropBlock cropBlock) {
            return cropBlock.isMaxAge(state);
        } else if (state.getBlock() instanceof NetherWartBlock) {
            return state.getValue(NetherWartBlock.AGE) >= 3;
        }
        return false;
    }

    /**
     * Returns true if {@code stack} is the correct seed/replant item for the given crop.
     *
     * For CropBlock, {@code asItem()} returns the seed via the ItemLike interface —
     * works for all CropBlock subclasses including modded ones, so no hardcoded
     * block→seed map is needed. Note: asItem() on the *crop block* gives the *seed*
     * (e.g. WHEAT_SEEDS for wheat, CARROT for carrots), not the harvested drop.
     */
    private static boolean isSeedFor(ItemStack stack, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock cropBlock) {
            return stack.is(cropBlock.asItem());
        }
        if (block instanceof CocoaBlock) {
            return stack.is(Items.COCOA_BEANS);
        }
        if (block instanceof NetherWartBlock) {
            return stack.is(Items.NETHER_WART);
        }
        return false;
    }

    /** Scans hotbar slots 0-8 for a matching seed for this crop; returns slot index or -1. */
    private static int findMatchingSeedInHotbar(Player player, BlockState state) {
        for (int slot = 0; slot < 9; slot++) {
            if (isSeedFor(player.getInventory().getItem(slot), state)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Lazily initialises and returns a reflected handle to the
     * package-private {@code Inventory.selected} field.
     */
    private static Field inventorySelectedField() {
        if (INVENTORY_SELECTED_FIELD == null) {
            try {
                INVENTORY_SELECTED_FIELD = net.minecraft.world.entity.player.Inventory.class
                        .getDeclaredField("selected");
                INVENTORY_SELECTED_FIELD.setAccessible(true);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException("[ClientSideHarvest] Could not find Inventory.selected field", e);
            }
        }
        return INVENTORY_SELECTED_FIELD;
    }

    /** Reads the current hotbar slot index. */
    private static int getSelectedSlot() {
        if (client.player == null) return 0;
        try {
            return (int) inventorySelectedField().get(client.player.getInventory());
        } catch (IllegalAccessException e) {
            throw new RuntimeException("[ClientSideHarvest] Could not read Inventory.selected", e);
        }
    }

    /** Writes the current hotbar slot locally (same-tick consistency) and notifies the server. */
    private static void switchHotbarSlot(int slot) {
        if (client.player == null) return;
        try {
            inventorySelectedField().set(client.player.getInventory(), slot);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("[ClientSideHarvest] Could not write Inventory.selected", e);
        }
        client.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
    }

    /**
     * Moves the item at {@code seedSlot} into the offhand (vanilla F-key behaviour)
     * then restores the previous hotbar slot so the fortune tool stays in the main hand.
     */
    private static void swapHotbarSlotToOffhand(int seedSlot) {
        if (client.player == null) return;

        int previousSlot = getSelectedSlot();

        // 1. Make the seed the active hotbar item
        switchHotbarSlot(seedSlot);

        // 2. Send the F-key swap packet (SWAP_ITEM_WITH_OFFHAND)
        client.player.connection.send(
                new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                        BlockPos.ZERO,
                        Direction.DOWN
                )
        );

        // 3. Restore the fortune tool to the main hand
        switchHotbarSlot(previousSlot);
    }

    /**
     * Returns true if the player's main-hand item has Fortune (any level).
     *
     * EnchantmentHelper.getItemEnchantmentLevel() requires a {@code Holder<Enchantment>},
     * not the raw {@code ResourceKey<Enchantment>} constant from {@link Enchantments}.
     * We resolve the Holder via the level's registry access.
     */
    private static boolean hasFortuneInMainHand(Player player, Level level) {
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.isEmpty()) return false;

        Holder<Enchantment> fortuneHolder = level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.FORTUNE);

        return EnchantmentHelper.getItemEnchantmentLevel(fortuneHolder, mainHand) > 0;
    }

    /**
     * Returns true if the stack is any recognised seed/replant item.
     * Used in Branch 2 as a fallback when no crop-specific match was found.
     */
    private static boolean isSeed(ItemStack stack) {
        if (stack.is(ItemTags.VILLAGER_PLANTABLE_SEEDS)) return true;
        if (stack.is(Items.COCOA_BEANS)) return true;
        if (stack.is(Items.NETHER_WART)) return true;
        return false;
    }

    private static void loadConfig() {
        try {
            if (!CONFIG_FILE.exists()) {
                saveConfig();
                return;
            }
            FileReader reader = new FileReader(CONFIG_FILE);
            Config config = GSON.fromJson(reader, Config.class);
            reader.close();
            if (config != null) {
                enabled = config.enabled;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void saveConfig() {
        try {
            CONFIG_FILE.getParentFile().mkdirs();
            Config config = new Config();
            config.enabled = enabled;
            FileWriter writer = new FileWriter(CONFIG_FILE);
            GSON.toJson(config, writer);
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}