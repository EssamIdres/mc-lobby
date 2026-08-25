package com.customblocks;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class GiveCommand implements CommandExecutor, TabCompleter {

    private final CustomBlockPlugin plugin;

    public GiveCommand(CustomBlockPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("customblock") || label.equalsIgnoreCase("cb")) {
            if (args.length == 0) {
                sender.sendMessage("§6§lCustomBlockGUI §7v1.1.0 §8(" + plugin.getAllBlockTypes().size() + " types)");
                sender.sendMessage("§7Commands:");
                sender.sendMessage("§e/givecustomblock <type> [player] [amount] §7- Give block");
                sender.sendMessage("§e/givecustomblock * §7- Give all types (inv full -> drop)");
                sender.sendMessage("§e/cb info §7- Plugin info");
                sender.sendMessage("§e/cb list §7- List all placed blocks with type");
                sender.sendMessage("§e/cb types §7- List all available types");
                sender.sendMessage("§e/cb removeall §7- Remove all (admin)");
                return true;
            }
            if (args[0].equalsIgnoreCase("info")) {
                sender.sendMessage("§6CustomBlockGUI §7v1.1.0 §8(New way, no Oraxen)");
                sender.sendMessage("§7DiscoveryLab " + plugin.getAllBlockTypes().size() + " textures via CustomModelData");
                sender.sendMessage("§7Total placed: §f" + plugin.getBlockTypes().size());
                for (CustomBlockPlugin.BlockType t : plugin.getAllBlockTypes()) {
                    if (t.id.equals("machine")) continue;
                    sender.sendMessage("§8- " + t.displayName + " §8(" + t.id + " CMD:" + t.customModelData + ")");
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("types")) {
                sender.sendMessage("§7Available types (" + plugin.getAllBlockTypes().size() + "):");
                for (CustomBlockPlugin.BlockType t : plugin.getAllBlockTypes()) {
                    sender.sendMessage("§8- §f" + t.id + " §7-> " + t.displayName + " §8(CMD " + t.customModelData + ")");
                }
                sender.sendMessage("§7Use: §f/givecustomblock <type> [amount]");
                return true;
            }
            if (args[0].equalsIgnoreCase("list")) {
                sender.sendMessage("§7Custom blocks (" + plugin.getBlockTypes().size() + "):");
                for (var e : plugin.getBlockTypes().entrySet()) {
                    var loc = plugin.keyToLoc(e.getKey());
                    if (loc != null) {
                        CustomBlockPlugin.BlockType def = plugin.getBlockTypeDef(e.getValue());
                        sender.sendMessage("§8- " + def.displayName + " §f" + loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ() + " §8(" + e.getValue() + ")");
                    } else {
                        sender.sendMessage("§8- §cInvalid: " + e.getKey() + " -> " + e.getValue());
                    }
                }
                // also legacy
                if (plugin.getBlockTypes().size() != plugin.getCustomBlocks().size()) {
                    sender.sendMessage("§7Legacy blocks: " + (plugin.getCustomBlocks().size() - plugin.getBlockTypes().size()));
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("removeall") && sender.hasPermission("customblock.give")) {
                int count = plugin.getBlockTypes().size();
                plugin.getBlockTypes().clear();
                plugin.getCustomBlocks().clear();
                plugin.getBlockStorages().clear();
                plugin.saveCustomBlocks();
                sender.sendMessage("§aRemoved " + count + " custom blocks (blocks stay but lose GUI).");
                return true;
            }
            sender.sendMessage("§cUnknown subcommand. Use /cb or /givecustomblock");
            return true;
        }

        // givecustomblock <type> [player] [amount] OR <player> [amount] OR <type>
        if (!sender.hasPermission("customblock.give")) {
            sender.sendMessage("§cNo permission!");
            return true;
        }

        String typeId = "machine";
        Player target = null;
        int amount = 1;

        // Parse: give all types with "*"
        if (args.length == 1 && args[0].equals("*")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("§cPlayer only for *"); return true; }
            target = p;
            for (CustomBlockPlugin.BlockType t : plugin.getAllBlockTypes()) {
                if (t.id.equals("machine")) continue;
                ItemStack it = plugin.createCustomBlockItem(t.id, 1);
                var leftover = target.getInventory().addItem(it);
                if (!leftover.isEmpty()) target.getWorld().dropItemNaturally(target.getLocation(), it);
            }
            sender.sendMessage("§a✓ Gave all " + (plugin.getAllBlockTypes().size()-1) + " DiscoveryLab blocks (different textures)!");
            sender.sendMessage("§7Each has different CustomModelData → different texture via resource pack (new way)");
            return true;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cUsage: /givecustomblock <type> [player] [amount] | available: " + String.join(", ", CustomBlockPlugin.BLOCK_TYPES.keySet()));
                return true;
            }
            target = (Player) sender;
            typeId = "machine";
        } else if (args.length == 1) {
            // Could be type or player
            String a0 = args[0].toLowerCase();
            if (CustomBlockPlugin.BLOCK_TYPES.containsKey(a0)) {
                typeId = a0;
                if (!(sender instanceof Player)) { sender.sendMessage("§cUsage: /givecustomblock <type> <player>"); return true; }
                target = (Player) sender;
            } else {
                // assume player
                target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    // maybe they typed type that doesn't exist -> treat as type anyway
                    if (args[0].contains("_") || args[0].length() > 2) {
                        sender.sendMessage("§cUnknown type '" + args[0] + "' | Use /cb types");
                        return true;
                    }
                    sender.sendMessage("§cPlayer not found: " + args[0]);
                    return true;
                }
            }
        } else if (args.length == 2) {
            // <type> <player> OR <type> <amount> OR <player> <amount>
            String a0 = args[0].toLowerCase();
            if (CustomBlockPlugin.BLOCK_TYPES.containsKey(a0)) {
                typeId = a0;
                // try player
                Player p = Bukkit.getPlayer(args[1]);
                if (p != null) {
                    target = p;
                } else {
                    try {
                        amount = Integer.parseInt(args[1]);
                        if (!(sender instanceof Player)) return true;
                        target = (Player) sender;
                    } catch (NumberFormatException e) {
                        sender.sendMessage("§cInvalid: " + args[1]);
                        return true;
                    }
                }
            } else {
                // <player> <amount>
                target = Bukkit.getPlayer(args[0]);
                if (target == null) { sender.sendMessage("§cPlayer not found"); return true; }
                try { amount = Integer.parseInt(args[1]); } catch (Exception e) { sender.sendMessage("§cInvalid amount"); return true; }
            }
        } else if (args.length >= 3) {
            // <type> <player> <amount>
            typeId = args[0].toLowerCase();
            if (!CustomBlockPlugin.BLOCK_TYPES.containsKey(typeId)) { sender.sendMessage("§cUnknown type " + typeId); return true; }
            target = Bukkit.getPlayer(args[1]);
            if (target == null) { sender.sendMessage("§cPlayer not found"); return true; }
            try { amount = Integer.parseInt(args[2]); } catch (Exception e) { sender.sendMessage("§cInvalid amount"); return true; }
        }

        amount = Math.max(1, Math.min(64, amount));
        CustomBlockPlugin.BlockType def = plugin.getBlockTypeDef(typeId);
        ItemStack item = plugin.createCustomBlockItem(typeId, amount);
        var leftover = target.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            for (ItemStack it : leftover.values()) target.getWorld().dropItemNaturally(target.getLocation(), it);
        }
        target.sendMessage("§a✓ Received " + def.displayName + " §ax" + amount + " §7(CMD " + def.customModelData + " → texture '" + typeId + "')");
        if (!target.equals(sender)) {
            sender.sendMessage("§aGave " + target.getName() + " x" + amount + " " + def.displayName);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (alias.equalsIgnoreCase("customblock") || alias.equalsIgnoreCase("cb")) {
            if (args.length == 1) {
                for (String s : List.of("info", "types", "list", "removeall")) {
                    if (s.startsWith(args[0].toLowerCase())) out.add(s);
                }
            }
            return out;
        }
        // givecustomblock
        List<String> types = new ArrayList<>(CustomBlockPlugin.BLOCK_TYPES.keySet());
        types.add("*");
        if (args.length == 1) {
            String low = args[0].toLowerCase();
            for (String t : types) if (t.startsWith(low)) out.add(t);
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(low)) out.add(p.getName());
        } else if (args.length == 2) {
            String low = args[1].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(low)) out.add(p.getName());
            for (String s : List.of("1", "16", "32", "64")) if (s.startsWith(low)) out.add(s);
            for (String t : types) if (t.startsWith(low)) out.add(t);
        } else if (args.length == 3) {
            for (String s : List.of("1", "16", "32", "64")) if (s.startsWith(args[2].toLowerCase())) out.add(s);
        }
        return out;
    }
}
