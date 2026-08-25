package com.customblocks;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public class CustomBlockListener implements Listener {

    private final CustomBlockPlugin plugin;

    public CustomBlockListener(CustomBlockPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItemInHand();

        if (plugin.isCustomBlockItem(item)) {
            Block block = event.getBlockPlaced();
            String type = plugin.getTypeFromItem(item);
            CustomBlockPlugin.BlockType def = plugin.getBlockTypeDef(type);

            // New way: use BARRIER as invisible base + ItemDisplay for custom texture (so placed looks right)
            // Keep SMITHING_TABLE for legacy, but new placements use BARRIER for clean rendering
            Block placed = event.getBlockPlaced();
            placed.setType(Material.BARRIER);

            Location loc = block.getLocation();
            plugin.addCustomBlock(loc, type);
            // Spawn display for placed texture
            plugin.spawnDisplay(loc, type);

            player.sendMessage("§a✓ " + def.displayName + " §aplaced! §7Right-click for GUI. §8(Shift+block to place against)");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Location loc = block.getLocation();

        if (plugin.isCustomBlock(loc)) {
            String typeId = plugin.getBlockTypeAt(loc);
            CustomBlockPlugin.BlockType def = plugin.getBlockTypeDef(typeId);
            event.setDropItems(false);
            event.setExpToDrop(0);

            // Remove display entity for placed texture
            plugin.removeDisplay(loc);

            // Drop storage contents first
            String key = plugin.locToKey(loc);
            ItemStack[] storage = plugin.getBlockStorages().get(key);
            if (storage != null) {
                for (ItemStack it : storage) {
                    if (it != null && it.getType() != Material.AIR) {
                        block.getWorld().dropItemNaturally(loc, it);
                    }
                }
            }

            plugin.removeCustomBlock(loc);

            // Drop the correct custom block item itself
            ItemStack drop = plugin.createCustomBlockItem(typeId, 1);
            block.getWorld().dropItemNaturally(loc, drop);

            event.getPlayer().sendMessage("§c✗ " + def.displayName + " §7broken! Storage dropped.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock();
        Location loc = block.getLocation();

        if (!plugin.isCustomBlock(loc)) return;

        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            // ALWAYS block vanilla GUI (smithing table) — never open vanilla
            event.setCancelled(true);
            // If sneaking + holding block -> allow placement against it, don't open custom GUI
            if (event.getPlayer().isSneaking() && event.getPlayer().getInventory().getItemInMainHand().getType().isBlock()) {
                // Let placement happen: we cancelled interact, so need to manually allow placement?
                // Instead, don't cancel placement: re-enable by not cancelling? But we already cancelled to block vanilla GUI.
                // So we simulate placement: but simpler — just don't open GUI, let player place via second click
                // For now, just do nothing (no GUI) so vanilla never opens, and player can place on next air click
                // Actually to allow placing, we need to NOT cancel. So un-cancel and return.
                event.setCancelled(false);
                return;
            }
            // Otherwise open custom GUI (covers normal click and shift+empty)
            GUIManager.openMainGUI(event.getPlayer(), loc);
        }
    }
}
