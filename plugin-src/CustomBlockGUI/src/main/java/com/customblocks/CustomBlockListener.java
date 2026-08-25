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
            // Ensure correct type (in case item was different)
            // Already SMITHING_TABLE, but force it
            // block.setType(CustomBlockPlugin.CUSTOM_BLOCK_MATERIAL);

            Location loc = block.getLocation();
            plugin.addCustomBlock(loc);

            player.sendMessage("§a✓ Custom Block placed! §7Right-click it to open GUI.");
            // Optional: prevent vanilla placement sound redo
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Location loc = block.getLocation();

        if (plugin.isCustomBlock(loc)) {
            event.setDropItems(false);
            event.setExpToDrop(0);

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

            // Drop the custom block item itself
            ItemStack drop = plugin.createCustomBlockItem(1);
            block.getWorld().dropItemNaturally(loc, drop);

            event.getPlayer().sendMessage("§c✗ Custom Block broken! §7Storage dropped if any.");

            // Ensure block becomes air (handle if event cancelled elsewhere)
            // Bukkit will handle.
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock();
        Location loc = block.getLocation();

        if (!plugin.isCustomBlock(loc)) return;

        // Only handle right-click block
        switch (event.getAction()) {
            case RIGHT_CLICK_BLOCK -> {
                // Allow shift-right-click to bypass GUI (for placing blocks against it)
                if (event.getPlayer().isSneaking()) {
                    // Let player place block if sneaking and holding block
                    if (event.getPlayer().getInventory().getItemInMainHand().getType().isBlock()) {
                        return;
                    }
                }
                event.setCancelled(true);
                GUIManager.openMainGUI(event.getPlayer(), loc);
            }
            case LEFT_CLICK_BLOCK -> {
                // Optional: left click info
                // Don't cancel, let break handle
            }
            default -> {}
        }
    }
}
