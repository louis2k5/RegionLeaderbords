package de.server.regionleaderboards;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class RegionLeaderboards extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<String, Location> leaderboards = new HashMap<>();
    private final Map<String, String> leaderboardTypes = new HashMap<>();
    private final Map<String, String> leaderboardRegions = new HashMap<>();
    private final Map<String, Map<UUID, Integer>> regionalKills = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();

        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("lb")).setExecutor(this);

        Bukkit.getScheduler().runTaskTimer(this, this::updateHolograms, 100L, 100L);
    }

    @Override
    public void onDisable() {
        saveData();
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer == null) return;

        com.sk89q.worldedit.util.Location loc = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(killer.getLocation());
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionQuery query = container.createQuery();
        ApplicableRegionSet set = query.getApplicableRegions(loc);

        for (ProtectedRegion region : set) {
            String regionName = region.getId().toLowerCase();
            regionalKills.putIfAbsent(regionName, new HashMap<>());
            Map<UUID, Integer> kills = regionalKills.get(regionName);
            kills.put(killer.getUniqueId(), kills.getOrDefault(killer.getUniqueId(), 0) + 1);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Dieser Befehl ist nur für Spieler.");
            return true;
        }

        if (args.length < 1) {
            sendHelp(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("create")) {
            if (args.length < 3) {
                player.sendMessage(ChatColor.RED + "Nutzung: /lb create <Name> <kills|deaths|playtime|region_kills> [WorldGuard-Region]");
                return true;
            }

            String name = args[1].toLowerCase();
            String type = args[2].toLowerCase();
            String region = args.length >= 4 ? args[3].toLowerCase() : "";

            if (type.equals("region_kills") && region.isEmpty()) {
                player.sendMessage(ChatColor.RED + "Bitte gib eine WorldGuard-Region an!");
                return true;
            }

            leaderboards.put(name, player.getLocation().add(0, 2, 0));
            leaderboardTypes.put(name, type);
            if (!region.isEmpty()) {
                leaderboardRegions.put(name, region);
            }

            saveData();
            updateHolograms();
            player.sendMessage(ChatColor.GREEN + "Leaderboard '" + name + "' erfolgreich erstellt!");
            return true;

        } else if (args[0].equalsIgnoreCase("remove")) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Nutzung: /lb remove <Name>");
                return true;
            }
            String name = args[1].toLowerCase();

            if (leaderboards.containsKey(name)) {
                removeHologramEntity(name);
                leaderboards.remove(name);
                leaderboardTypes.remove(name);
                leaderboardRegions.remove(name);
                saveData();
                player.sendMessage(ChatColor.GREEN + "Leaderboard '" + name + "' gelöscht.");
            } else {
                player.sendMessage(ChatColor.RED + "Leaderboard nicht gefunden.");
            }
            return true;

        } else if (args[0].equalsIgnoreCase("list")) {
            player.sendMessage(ChatColor.GOLD + "=== Aktive Leaderboards ===");
            for (String lb : leaderboards.keySet()) {
                player.sendMessage(ChatColor.YELLOW + "- " + lb + " (" + leaderboardTypes.get(lb) + ")");
            }
            return true;
        }

        sendHelp(player);
        return true;
    }

    private void updateHolograms() {
        for (Map.Entry<String, Location> entry : leaderboards.entrySet()) {
            String name = entry.getKey();
            Location loc = entry.getValue();
            String type = leaderboardTypes.get(name);
            String region = leaderboardRegions.getOrDefault(name, "");

            String text = buildText(name, type, region);
            spawnOrUpdateHologram(name, loc, text);
        }
    }

    private String buildText(String lbName, String type, String region) {
        StringBuilder sb = new StringBuilder();

        switch (type) {
            case "kills" -> {
                sb.append("§e§lTop All-Time Kills\n");
                List<Map.Entry<String, Integer>> list = getGlobalStats(Statistic.PLAYER_KILLS);
                appendTopList(sb, list, "Kills");
            }
            case "deaths" -> {
                sb.append("§c§lTop All-Time Deaths\n");
                List<Map.Entry<String, Integer>> list = getGlobalStats(Statistic.DEATHS);
                appendTopList(sb, list, "Tode");
            }
            case "playtime" -> {
                sb.append("§a§lTop Spielzeit\n");
                List<Map.Entry<String, Integer>> list = getGlobalPlaytime();
                for (int i = 0; i < Math.min(5, list.size()); i++) {
                    var e = list.get(i);
                    int hours = e.getValue() / 72000;
                    sb.append("§7").append(i + 1).append(". §f").append(e.getKey()).append(": §e").append(hours).append(" Std.\n");
                }
            }
            case "region_kills" -> {
                sb.append("§6§lTop Kills in Region: §e").append(region).append("\n");
                List<Map.Entry<String, Integer>> list = getRegionStats(region);
                appendTopList(sb, list, "Kills");
            }
        }
        return sb.toString();
    }

    private void appendTopList(StringBuilder sb, List<Map.Entry<String, Integer>> list, String unit) {
        for (int i = 0; i < Math.min(5, list.size()); i++) {
            var e = list.get(i);
            sb.append("§7").append(i + 1).append(". §f").append(e.getKey()).append(": §e").append(e.getValue()).append(" ").append(unit).append("\n");
        }
        if (list.isEmpty()) {
            sb.append("§7Keine Daten vorhanden.");
        }
    }

    private List<Map.Entry<String, Integer>> getGlobalStats(Statistic stat) {
        Map<String, Integer> map = new HashMap<>();
        for (var p : Bukkit.getOfflinePlayers()) {
            int val = p.getStatistic(stat);
            if (val > 0) map.put(p.getName() != null ? p.getName() : "Unbekannt", val);
        }
        return sortMap(map);
    }

    private List<Map.Entry<String, Integer>> getGlobalPlaytime() {
        Map<String, Integer> map = new HashMap<>();
        for (var p : Bukkit.getOfflinePlayers()) {
            int val = p.getStatistic(Statistic.PLAY_ONE_MINUTE);
            if (val > 0) map.put(p.getName() != null ? p.getName() : "Unbekannt", val);
        }
        return sortMap(map);
    }

    private List<Map.Entry<String, Integer>> getRegionStats(String region) {
        Map<String, Integer> map = new HashMap<>();
        Map<UUID, Integer> kills = regionalKills.getOrDefault(region.toLowerCase(), new HashMap<>());

        for (var entry : kills.entrySet()) {
            var op = Bukkit.getOfflinePlayer(entry.getKey());
            map.put(op.getName() != null ? op.getName() : "Unbekannt", entry.getValue());
        }
        return sortMap(map);
    }

    private List<Map.Entry<String, Integer>> sortMap(Map<String, Integer> map) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(map.entrySet());
        list.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        return list;
    }

    private void spawnOrUpdateHologram(String name, Location loc, String text) {
        TextDisplay display = null;

        for (Entity e : loc.getWorld().getNearbyEntities(loc, 2, 2, 2)) {
            if (e instanceof TextDisplay td && td.getScoreboardTags().contains("lb_" + name)) {
                display = td;
                break;
            }
        }

        if (display == null) {
            display = loc.getWorld().spawn(loc, TextDisplay.class);
            display.addScoreboardTag("lb_" + name);
            display.setBillboard(Display.Billboard.CENTER);
            display.setShadowed(true);
        }

        display.setText(text);
    }

    private void removeHologramEntity(String name) {
        Location loc = leaderboards.get(name);
        if (loc == null || loc.getWorld() == null) return;

        for (Entity e : loc.getWorld().getNearbyEntities(loc, 3, 3, 3)) {
            if (e instanceof TextDisplay td && td.getScoreboardTags().contains("lb_" + name)) {
                td.remove();
            }
        }
    }

    private void loadData() {
        FileConfiguration config = getConfig();
        if (config.contains("boards")) {
            for (String key : config.getConfigurationSection("boards").getKeys(false)) {
                Location loc = config.getLocation("boards." + key + ".loc");
                String type = config.getString("boards." + key + ".type");
                String region = config.getString("boards." + key + ".region", "");

                leaderboards.put(key, loc);
                leaderboardTypes.put(key, type);
                if (!region.isEmpty()) leaderboardRegions.put(key, region);
            }
        }
        if (config.contains("region_data")) {
            for (String reg : config.getConfigurationSection("region_data").getKeys(false)) {
                Map<UUID, Integer> map = new HashMap<>();
                for (String uuidStr : config.getConfigurationSection("region_data." + reg).getKeys(false)) {
                    map.put(UUID.fromString(uuidStr), config.getInt("region_data." + reg + "." + uuidStr));
                }
                regionalKills.put(reg, map);
            }
        }
    }

    private void saveData() {
        FileConfiguration config = getConfig();
        config.set("boards", null);
        config.set("region_data", null);

        for (String key : leaderboards.keySet()) {
            config.set("boards." + key + ".loc", leaderboards.get(key));
            config.set("boards." + key + ".type", leaderboardTypes.get(key));
            config.set("boards." + key + ".region", leaderboardRegions.getOrDefault(key, ""));
        }

        for (var regEntry : regionalKills.entrySet()) {
            for (var playerEntry : regEntry.getValue().entrySet()) {
                config.set("region_data." + regEntry.getKey() + "." + playerEntry.getKey().toString(), playerEntry.getValue());
            }
        }

        saveConfig();
    }

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== Leaderboard Befehle ===");
        player.sendMessage(ChatColor.YELLOW + "/lb create <Name> <Typ> [Region] §7- Erstellt Hologramm an deiner Position");
        player.sendMessage(ChatColor.YELLOW + "/lb remove <Name> §7- Löscht ein Leaderboard");
        player.sendMessage(ChatColor.YELLOW + "/lb list §7- Listet alle Leaderboards auf");
        player.sendMessage(ChatColor.GRAY + "Typen: kills, deaths, playtime, region_kills");
    }
}
