package com.customblocks;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class GUIManager implements Listener {

    private final CustomBlockPlugin plugin;
    private final NamespacedKey guiKey;

    // Track open storage per player: player UUID -> locationKey
    private final Map<UUID, String> openStorages = new HashMap<>();
    private final Map<UUID, Location> openMenus = new HashMap<>();

    public static final String MAIN_TITLE = "§8§lCustom Machine §7- Menu";
    public static final String STORAGE_TITLE = "§8§lCustom Storage §7- 27 Slots";

    public GUIManager(CustomBlockPlugin plugin) {
        this.plugin = plugin;
        this.guiKey = new NamespacedKey(plugin, "gui_item");
    }

    // === OPEN GUIS ===
    public static void openMainGUI(Player player, Location blockLoc) {
        CustomBlockPlugin plugin = CustomBlockPlugin.getInstance();
        String typeId = plugin.getBlockTypeAt(blockLoc);
        CustomBlockPlugin.BlockType def = plugin.getBlockTypeDef(typeId);
        String title = "§8§l" + def.displayName.replace("§l","").replace("§d","").replace("§6","").replace("§e","").replace("§8","").replace("§7","").replace("§5","").replace("§c","").replace("§a","").replace("§f","").replace("§9","").replace("§b","").trim() + " §7- Menu";
        // Keep generic title for click detection, but show typed
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(def.displayName + " §7- Menu"));

        // Fill with gray glass
        ItemStack filler = createFiller();
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);

        // Slot 11 - Storage
        ItemStack storage = new ItemStack(Material.CHEST);
        ItemMeta m1 = storage.getItemMeta();
        m1.displayName(Component.text("§e§lStorage §7(Click to open)"));
        m1.lore(List.of(
                Component.text("§7Store items inside your"),
                Component.text("§7custom block!"),
                Component.text(""),
                Component.text("§7Contents stay inside"),
                Component.text("§7even after restart."),
                Component.text(""),
                Component.text("§a► Click to open")
        ));
        m1.getPersistentDataContainer().set(new NamespacedKey(plugin, "gui_action"), PersistentDataType.STRING, "storage");
        storage.setItemMeta(m1);
        inv.setItem(11, storage);

        // Slot 13 - Per-machine feature (different per type)
        ItemStack craft = createFeatureItem(def);
        craft.getItemMeta().getPersistentDataContainer().set(new NamespacedKey(plugin, "gui_action"), PersistentDataType.STRING, "craft");
        // need to re-set because helper already sets? ensure
        ItemMeta m2 = craft.getItemMeta();
        m2.getPersistentDataContainer().set(new NamespacedKey(plugin, "gui_action"), PersistentDataType.STRING, "craft");
        craft.setItemMeta(m2);
        inv.setItem(13, craft);

        // Slot 15 - Info (per-type)
        String typeAt = plugin.getBlockTypeAt(blockLoc);
        CustomBlockPlugin.BlockType infoDef = plugin.getBlockTypeDef(typeAt);
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta m3 = info.getItemMeta();
        m3.displayName(Component.text("§a§lInformation"));
        m3.lore(List.of(
                Component.text("§7Block: " + infoDef.displayName + " §8(" + infoDef.id + ")"),
                Component.text("§7CMD: §f" + infoDef.customModelData + " §7Mat: §f" + infoDef.material),
                Component.text("§7At: §f" + blockLoc.getBlockX() + "§7, §f" + blockLoc.getBlockY() + "§7, §f" + blockLoc.getBlockZ()),
                Component.text("§7World: §f" + blockLoc.getWorld().getName()),
                Component.text(""),
                Component.text("§7This is a §eDiscoveryLab§7 block"),
                Component.text("§7with its own storage (new way)"),
                Component.text("§7Break to get items back."),
                Component.text(""),
                Component.text("§8CustomBlockGUI v1.1.0")
        ));
        m3.getPersistentDataContainer().set(new NamespacedKey(plugin, "gui_action"), PersistentDataType.STRING, "info");
        info.setItemMeta(m3);
        inv.setItem(15, info);

        // Slot 22 - Close
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta m4 = close.getItemMeta();
        m4.displayName(Component.text("§c§lClose"));
        m4.lore(List.of(Component.text("§7Click to close menu")));
        m4.getPersistentDataContainer().set(new NamespacedKey(plugin, "gui_action"), PersistentDataType.STRING, "close");
        close.setItemMeta(m4);
        inv.setItem(22, close);

        // Store location in player's metadata via our map (access via singleton)
        // Hack: use plugin's map via instance lookup
        // We need to find GUIManager instance registered as listener - iterate
        for (var handler : Bukkit.getPluginManager().getPlugin("CustomBlockGUI").getServer().getPluginManager().getPlugins()) {}
        // Simpler: store in static map
        openMenusStatic.put(player.getUniqueId(), blockLoc);

        player.openInventory(inv);
        CustomBlockPlugin.BlockType openedDef = plugin.getBlockTypeDef(plugin.getBlockTypeAt(blockLoc));
        player.sendMessage("§7Opened " + openedDef.displayName + " §7menu §8(" + blockLoc.getBlockX() + "," + blockLoc.getBlockY() + "," + blockLoc.getBlockZ() + "§8) §7CMD:" + openedDef.customModelData);
    }

    private static final Map<UUID, Location> openMenusStatic = new HashMap<>();
    private static final Map<UUID, String> openStoragesStatic = new HashMap<>();

    public static void openStorageGUI(Player player, Location blockLoc) {
        CustomBlockPlugin plugin = CustomBlockPlugin.getInstance();
        String key = plugin.locToKey(blockLoc);
        ItemStack[] stored = plugin.getStorage(key);

        Inventory inv = Bukkit.createInventory(null, 27, Component.text(STORAGE_TITLE));

        for (int i = 0; i < 27; i++) {
            if (stored[i] != null) inv.setItem(i, stored[i]);
        }

        openStoragesStatic.put(player.getUniqueId(), key);
        openMenusStatic.put(player.getUniqueId(), blockLoc);

        player.openInventory(inv);
    }

    private static ItemStack createFiller() {
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = glass.getItemMeta();
        meta.displayName(Component.text(" "));
        glass.setItemMeta(meta);
        return glass;
    }

    private static ItemStack createFeatureItem(CustomBlockPlugin.BlockType def) {
        Material mat = Material.CRAFTING_TABLE;
        String name = "§b§lFeature";
        List<Component> lore = new ArrayList<>();
        switch (def.id) {
            case "altar" -> { mat = Material.TOTEM_OF_UNDYING; name = "§d§lAltar §7- Craft Totem"; lore = List.of(Component.text("§7Craft §fTotem Shard §7→ §fTotem"), Component.text("§7Cost: 4x totem_shard"), Component.text(""), Component.text("§e► Click to craft")); }
            case "autocrafter" -> { mat = Material.CRAFTING_TABLE; name = "§6§lAutocrafter §7- Toggle"; lore = List.of(Component.text("§7Auto-crafts recipe every 5s"), Component.text("§7Put recipe in Storage"), Component.text(""), Component.text("§e► Click to toggle ON/OFF")); }
            case "belt", "belt_machine" -> { mat = Material.POWERED_RAIL; name = "§e§lBelt §7- Speed"; lore = List.of(Component.text("§7Conveyor speed setting"), Component.text("§7Currently: §fNormal"), Component.text(""), Component.text("§e► Click to change")); }
            case "crusher" -> { mat = Material.COBBLESTONE; name = "§8§lCrusher §7- Crush"; lore = List.of(Component.text("§7Crush §fIron Ore → 2x Dust"), Component.text("§7Input: Storage slot 0"), Component.text(""), Component.text("§e► Click to crush")); }
            case "drill" -> { mat = Material.DIAMOND_PICKAXE; name = "§7§lDrill §7- Mine"; lore = List.of(Component.text("§7Mines block below"), Component.text("§7Drops to Storage"), Component.text(""), Component.text("§e► Click to drill")); }
            case "druglab" -> { mat = Material.POTION; name = "§5§lDrug Lab §7- Brew"; lore = List.of(Component.text("§7Brew §fraw_drug → essence"), Component.text(""), Component.text("§e► Click to brew")); }
            case "generator" -> { mat = Material.REDSTONE_BLOCK; name = "§c§lGenerator §7- Power"; lore = List.of(Component.text("§7Generates power from coal"), Component.text("§7Fuel: Storage"), Component.text(""), Component.text("§e► Click to generate")); }
            case "packaging" -> { mat = Material.PAPER; name = "§a§lPackaging §7- Pack"; lore = List.of(Component.text("§7Pack 9x → 1x"), Component.text(""), Component.text("§e► Click to pack")); }
            case "pipe" -> { mat = Material.HOPPER; name = "§f§lPipe §7- Filter"; lore = List.of(Component.text("§7Set item filter"), Component.text(""), Component.text("§e► Click to set filter")); }
            case "press" -> { mat = Material.ANVIL; name = "§6§lPress §7- Press"; lore = List.of(Component.text("§7Press §fSteel Ingot → Plate"), Component.text(""), Component.text("§e► Click to press")); }
            case "sorter" -> { mat = Material.COMPARATOR; name = "§9§lSorter §7- Sort"; lore = List.of(Component.text("§7Sorts Storage"), Component.text(""), Component.text("§e► Click to sort")); }
            case "spawnercore" -> { mat = Material.SPAWNER; name = "§5§lSpawner Core §7- Upgrade"; lore = List.of(Component.text("§7Upgrade spawner"), Component.text(""), Component.text("§e► Click to upgrade")); }
            case "splitter" -> { mat = Material.CHEST; name = "§b§lSplitter §7- Split"; lore = List.of(Component.text("§7Split items 50/50"), Component.text(""), Component.text("§e► Click to split")); }
            case "totem_machine" -> { mat = Material.TOTEM_OF_UNDYING; name = "§a§lTotem Machine §7- Craft"; lore = List.of(Component.text("§7Craft totem from shards"), Component.text(""), Component.text("§e► Click")); }
            case "copper_forge" -> { mat = Material.COPPER_INGOT; name = "§6§lCopper Forge §7- Smelt"; lore = List.of(Component.text("§7Smelt §fCopper → Brass"), Component.text(""), Component.text("§e► Click to smelt")); }
            default -> { mat = Material.CRAFTING_TABLE; name = "§b§lFeature"; lore = List.of(Component.text("§7Generic feature"), Component.text(""), Component.text("§e► Click")); }
        }
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(name));
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.getView().title());

        // Check if it's our GUI by title contains (now per-type title contains " - Menu")
        boolean isMain = title.contains(" - Menu") || title.contains("Custom Machine");
        boolean isStorage = title.contains("Custom Storage");

        if (!isMain && !isStorage) return;

        if (isStorage) {
            // Allow taking/placing items in storage, but handle bottom inventory clicks normally
            // Only prevent moving filler? No filler in storage, so allow everything.
            // But we need to track storage location - do nothing on click, save on close.
            // Prevent shift-click exploiting? Allow.
            // Cancel only if clicking outside?
            // Let it pass, but save on close.
            // Optionally block dragging storage title items? no.
            return;
        }

        // Main GUI - cancel all clicks
        event.setCancelled(true);

        if (event.getCurrentItem() == null) return;
        ItemStack item = event.getCurrentItem();
        if (!item.hasItemMeta()) return;

        String action = item.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(plugin, "gui_action"), PersistentDataType.STRING);
        if (action == null) return;

        Location loc = openMenusStatic.get(player.getUniqueId());
        if (loc == null) {
            // Try to find nearest custom block? fallback
            player.closeInventory();
            return;
        }

        switch (action) {
            case "storage" -> {
                // Open storage for this block
                player.closeInventory();
                // Delay 1 tick to avoid close/open conflict
                Bukkit.getScheduler().runTask(plugin, () -> openStorageGUI(player, loc));
            }
            case "craft" -> {
                String t = plugin.getBlockTypeAt(loc);
                CustomBlockPlugin.BlockType d = plugin.getBlockTypeDef(t);
                ItemStack give = null;
                String msg = "§b◆ " + d.displayName + " §7feature!";
                org.bukkit.Sound sound = org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
                switch (d.id) {
                    case "altar" -> { give = new ItemStack(Material.TOTEM_OF_UNDYING); msg = "§d◆ Altar crafted Totem!"; sound = org.bukkit.Sound.BLOCK_BEACON_ACTIVATE; }
                    case "autocrafter" -> { msg = "§6◆ Autocrafter toggled! (demo)"; sound = org.bukkit.Sound.BLOCK_PISTON_EXTEND; }
                    case "belt", "belt_machine" -> { give = new ItemStack(Material.POWERED_RAIL); msg = "§e◆ Belt speed changed!"; }
                    case "crusher" -> {
                        // Try to crush iron ore in storage slot 0
                        String key = plugin.locToKey(loc);
                        ItemStack[] stor = plugin.getStorage(key);
                        boolean crushed = false;
                        for (int i=0;i<stor.length;i++) if (stor[i]!=null && stor[i].getType()==Material.IRON_ORE) { stor[i].setAmount(stor[i].getAmount()-1); if (stor[i].getAmount()<=0) stor[i]=null; give = new ItemStack(Material.IRON_INGOT, 2); msg="§8◆ Crusher: Iron Ore → 2x Iron!"; crushed=true; plugin.saveStorage(key, stor); break; }
                        if (!crushed) { give = new ItemStack(Material.IRON_INGOT, 2); msg="§8◆ Crusher demo: 2x Iron (add ore to storage slot 0 to crush)"; }
                    }
                    case "drill" -> { give = new ItemStack(Material.COBBLESTONE, 3); msg="§7◆ Drill mined 3x Cobblestone!"; sound = org.bukkit.Sound.BLOCK_STONE_BREAK; }
                    case "druglab" -> { give = new ItemStack(Material.POTION); msg="§5◆ Drug Lab brewed Potion!"; }
                    case "generator" -> { give = new ItemStack(Material.REDSTONE, 5); msg="§c◆ Generator produced 5x Redstone!"; sound = org.bukkit.Sound.BLOCK_FURNACE_FIRE_CRACKLE; }
                    case "packaging" -> { give = new ItemStack(Material.PAPER, 9); msg="§a◆ Packaging packed 9x Paper!"; }
                    case "pipe" -> { msg="§f◆ Pipe filter set! (demo)"; }
                    case "press" -> { give = new ItemStack(Material.IRON_INGOT); msg="§6◆ Press pressed Steel Plate!"; sound = org.bukkit.Sound.BLOCK_ANVIL_USE; }
                    case "sorter" -> {
                        String key2 = plugin.locToKey(loc);
                        ItemStack[] stor2 = plugin.getStorage(key2);
                        Arrays.sort(stor2, Comparator.comparing(s -> s==null? "zzz" : s.getType().toString()));
                        plugin.saveStorage(key2, stor2);
                        msg="§9◆ Sorter sorted Storage!";
                        sound = org.bukkit.Sound.BLOCK_CHEST_OPEN;
                    }
                    case "spawnercore" -> { give = new ItemStack(Material.SPAWNER); msg="§5◆ Spawner Core upgraded!"; }
                    case "splitter" -> { msg="§b◆ Splitter split items!"; }
                    case "totem_machine" -> { give = new ItemStack(Material.TOTEM_OF_UNDYING); msg="§a◆ Totem Machine crafted Totem!"; }
                    case "copper_forge" -> { give = new ItemStack(Material.COPPER_INGOT, 2); msg="§6◆ Copper Forge smelted 2x Copper → Brass!"; sound = org.bukkit.Sound.BLOCK_FURNACE_FIRE_CRACKLE; }
                    default -> { give = new ItemStack(Material.DIAMOND, 1); msg="§b◆ Feature demo!"; }
                }
                player.sendMessage(msg);
                if (give != null) {
                    var leftover = player.getInventory().addItem(give);
                    if (!leftover.isEmpty()) for (ItemStack it : leftover.values()) player.getWorld().dropItemNaturally(player.getLocation(), it);
                }
                player.playSound(player.getLocation(), sound, 1f, 1f);
            }
            case "info" -> {
                String t = plugin.getBlockTypeAt(loc);
                CustomBlockPlugin.BlockType d = plugin.getBlockTypeDef(t);
                player.sendMessage("§a--- Custom Block Info ---");
                player.sendMessage("§7Block: " + d.displayName + " §7(" + d.id + ") CMD:" + d.customModelData);
                player.sendMessage("§7Location: §f" + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ());
                player.sendMessage("§7World: §f" + loc.getWorld().getName());
                player.sendMessage("§7Texture: §f" + d.description);
                player.sendMessage("§7Edit §eGUIManager.java §7to customize!");
                player.sendMessage("§7Tip: Different texture per CustomModelData " + d.customModelData + " via DiscoveryLab pack");
            }
            case "close" -> player.closeInventory();
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        String title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.getView().title());

        if (title.contains("Custom Storage")) {
            String key = openStoragesStatic.remove(player.getUniqueId());
            if (key != null) {
                ItemStack[] contents = event.getInventory().getContents();
                // Clone to avoid reference issues
                ItemStack[] copy = new ItemStack[27];
                for (int i = 0; i < 27; i++) copy[i] = contents[i] != null ? contents[i].clone() : null;
                plugin.saveStorage(key, copy);
                player.sendMessage("§a✓ Storage saved!");
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_CHEST_CLOSE, 0.8f, 1f);
            }
            // Keep menu location for possible return?
        }
        if (title.contains(" - Menu")) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!openStoragesStatic.containsKey(player.getUniqueId())) {
                    openMenusStatic.remove(player.getUniqueId());
                }
            }, 2L);
        }
    }
}
