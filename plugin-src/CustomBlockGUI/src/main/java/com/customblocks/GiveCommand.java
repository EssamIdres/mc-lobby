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
                sender.sendMessage("§6§lCustomBlockGUI §7v1.0.0");
                sender.sendMessage("§7Commands:");
                sender.sendMessage("§e/givecustomblock §7- Give custom block");
                sender.sendMessage("§e/cb info §7- Plugin info");
                sender.sendMessage("§e/cb list §7- List all custom blocks");
                sender.sendMessage("§e/cb removeall §7- Remove all (admin)");
                return true;
            }
            if (args[0].equalsIgnoreCase("info")) {
                sender.sendMessage("§6CustomBlockGUI §7by CustomBlocks");
                sender.sendMessage("§7Place the §6Custom Machine §7and right-click for GUI");
                sender.sendMessage("§7Block type: §f" + CustomBlockPlugin.CUSTOM_BLOCK_MATERIAL);
                sender.sendMessage("§7Total placed: §f" + plugin.getCustomBlocks().size());
                return true;
            }
            if (args[0].equalsIgnoreCase("list")) {
                sender.sendMessage("§7Custom blocks (" + plugin.getCustomBlocks().size() + "):");
                for (String key : plugin.getCustomBlocks()) {
                    var loc = plugin.keyToLoc(key);
                    if (loc != null) {
                        sender.sendMessage("§8- §f" + loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ());
                    } else {
                        sender.sendMessage("§8- §cInvalid: " + key);
                    }
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("removeall") && sender.hasPermission("customblock.give")) {
                int count = plugin.getCustomBlocks().size();
                // Remove blocks visually? Just clear data
                plugin.getCustomBlocks().clear();
                plugin.getBlockStorages().clear();
                plugin.saveCustomBlocks();
                sender.sendMessage("§aRemoved " + count + " custom blocks from database (blocks stay in world but lose GUI). Break & Replace to restore.");
                return true;
            }
            sender.sendMessage("§cUnknown subcommand. Use /cb or /givecustomblock");
            return true;
        }

        // givecustomblock
        if (!sender.hasPermission("customblock.give")) {
            sender.sendMessage("§cNo permission!");
            return true;
        }

        Player target;
        int amount = 1;

        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cUsage: /givecustomblock <player> [amount]");
                return true;
            }
            target = (Player) sender;
        } else {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found: " + args[0]);
                return true;
            }
            if (args.length >= 2) {
                try {
                    amount = Integer.parseInt(args[1]);
                    amount = Math.max(1, Math.min(64, amount));
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cInvalid amount");
                    return true;
                }
            }
        }

        ItemStack item = plugin.createCustomBlockItem(amount);
        target.getInventory().addItem(item);
        target.sendMessage("§a✓ Received §6Custom Machine §ax" + amount + " §7(Place & right-click)");
        if (!target.equals(sender)) {
            sender.sendMessage("§aGave " + target.getName() + " x" + amount + " custom block");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (alias.equalsIgnoreCase("customblock") || alias.equalsIgnoreCase("cb")) {
            if (args.length == 1) {
                for (String s : List.of("info", "list", "removeall")) {
                    if (s.startsWith(args[0].toLowerCase())) out.add(s);
                }
            }
            return out;
        }
        // givecustomblock
        if (args.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[0].toLowerCase())) out.add(p.getName());
            }
        } else if (args.length == 2) {
            for (String s : List.of("1", "16", "32", "64")) {
                if (s.startsWith(args[1])) out.add(s);
            }
        }
        return out;
    }
}
