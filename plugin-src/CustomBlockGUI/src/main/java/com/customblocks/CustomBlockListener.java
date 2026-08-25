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
            // Force correct material (in case of different type materials)
            if (block.getType() != def.material) {
                block.setType(def.material);
            }

            Location loc = block.getLocation();
            plugin.addCustomBlock(loc, type);

            player.sendMessage("§a✓ " + def.displayName + " §aplaced! §7Right-click for GUI.");
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

        switch (event.getAction()) {
            case RIGHT_CLICK_BLOCK -> {
                if (event.getPlayer().isSneaking()) {
                    if (event.getPlayer().getInventory().getItemInMainHand().getType().isBlock()) {
                        return;
                    }
                }
                event.setCancelled(true);
                GUIManager.openMainGUI(event.getPlayer(), loc);
            }
            case LEFT_CLICK_BLOCK -> {}
            default -> {}
        }
    }
}
