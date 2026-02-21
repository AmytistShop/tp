package me.crabcraft.ttv;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TeleportTimeVanishPlugin extends JavaPlugin implements Listener, TabExecutor {

    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    private LegacyComponentSerializer legacy() {
        return LegacyComponentSerializer.legacyAmpersand();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("day")).setExecutor(this);
        Objects.requireNonNull(getCommand("night")).setExecutor(this);
        Objects.requireNonNull(getCommand("vanish")).setExecutor(this);
        Objects.requireNonNull(getCommand("gm")).setExecutor(this);
        Objects.requireNonNull(getCommand("clearchat")).setExecutor(this);

        getLogger().info("TeleportTimeVanish enabled.");
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (vanished.contains(p.getUniqueId())) {
                applyVanish(p, false);
            }
        }
        vanished.clear();
    }

    // Teleport notifications (tp to a player)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!getConfig().getBoolean("teleport.enabled", true)) return;

        PlayerTeleportEvent.TeleportCause cause = e.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.COMMAND && cause != PlayerTeleportEvent.TeleportCause.PLUGIN) return;

        Player teleporter = e.getPlayer();
        if (!teleporter.hasPermission("ttv.teleportnotify")) return;

        Location to = e.getTo();
        if (to == null || to.getWorld() == null) return;

        double radius = getConfig().getDouble("teleport.detect-radius", 1.0);
        Player target = findTargetPlayer(to, teleporter, radius);
        if (target == null) return;

        boolean sendToTeleporter = getConfig().getBoolean("teleport.send-to-teleporter", true);
        boolean sendToTarget = getConfig().getBoolean("teleport.send-to-target", true);

        String prefix = getConfig().getString("prefix", "");
        Map<String, String> ph = new HashMap<>();
        ph.put("prefix", prefix);
        ph.put("player", teleporter.getName());
        ph.put("target", target.getName());
        ph.put("world", to.getWorld().getName());

        if (sendToTeleporter) {
            String msg = getConfig().getString("teleport.teleporter-message", "{prefix}&aTeleported to {target}");
            teleporter.sendMessage(colorize(applyPlaceholders(msg, ph)));
        }
        if (sendToTarget) {
            String msg = getConfig().getString("teleport.target-message", "{prefix}&e{player}&a teleported to you.");
            target.sendMessage(colorize(applyPlaceholders(msg, ph)));
        }
    }

    private Player findTargetPlayer(Location to, Player exclude, double radius) {
        World w = to.getWorld();
        Player best = null;
        double bestDist = Double.MAX_VALUE;

        for (Player p : w.getPlayers()) {
            if (p.equals(exclude)) continue;
            double d = p.getLocation().distance(to);
            if (d <= radius && d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    // Keep vanished players hidden from joiner
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player joiner = e.getPlayer();
        if (!getConfig().getBoolean("vanish.hide-player", true)) return;

        for (UUID id : vanished) {
            Player v = Bukkit.getPlayer(id);
            if (v != null && v.isOnline() && !v.equals(joiner)) {
                joiner.hidePlayer(this, v);
            }
        }
    }

    // Commands
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

        if (name.equals("day") || name.equals("night") || name.equals("vanish") || name.equals("gm")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use this command.");
                return true;
            }

            if (name.equals("day")) return cmdTime(player, "day");
            if (name.equals("night")) return cmdTime(player, "night");
            if (name.equals("vanish")) return cmdVanish(player);
            return cmdGamemode(player, args);
        }

        if (name.equals("clearchat")) {
            return cmdClearChat(sender);
        }

        return false;
    }

    private boolean cmdTime(Player player, String timeKey) {
        if (!getConfig().getBoolean("time.enabled", true)) {
            player.sendMessage(colorize(getConfig().getString("prefix", "") + "&cКоманда отключена."));
            return true;
        }
        long ticks = timeKey.equals("day")
                ? getConfig().getLong("time.day-ticks", 1000)
                : getConfig().getLong("time.night-ticks", 13000);

        World w = player.getWorld();
        w.setTime(ticks);

        String prefix = getConfig().getString("prefix", "");
        Map<String, String> ph = new HashMap<>();
        ph.put("prefix", prefix);
        ph.put("player", player.getName());
        ph.put("world", w.getName());
        ph.put("time", timeKey);

        String senderMsg = getConfig().getString("time.sender-message", "{prefix}&aTime set to {time}.");
        player.sendMessage(colorize(applyPlaceholders(senderMsg, ph)));

        if (getConfig().getBoolean("time.broadcast", true)) {
            String bc = getConfig().getString("time.broadcast-message", "{prefix}&e{player}&a set time to {time}.");
            Bukkit.broadcast(colorize(applyPlaceholders(bc, ph)));
        }
        return true;
    }

    private boolean cmdVanish(Player player) {
        if (!getConfig().getBoolean("vanish.enabled", true)) {
            player.sendMessage(colorize(getConfig().getString("prefix", "") + "&cКоманда отключена."));
            return true;
        }

        boolean enable = !vanished.contains(player.getUniqueId());
        if (enable) vanished.add(player.getUniqueId());
        else vanished.remove(player.getUniqueId());

        applyVanish(player, enable);

        String prefix = getConfig().getString("prefix", "");
        String msg = enable
                ? getConfig().getString("vanish.enabled-message", "{prefix}&aVanish enabled.")
                : getConfig().getString("vanish.disabled-message", "{prefix}&cVanish disabled.");

        Map<String, String> ph = new HashMap<>();
        ph.put("prefix", prefix);
        ph.put("player", player.getName());

        player.sendMessage(colorize(applyPlaceholders(msg, ph)));
        return true;
    }

    private void applyVanish(Player player, boolean enable) {
        boolean hide = getConfig().getBoolean("vanish.hide-player", true);
        boolean potion = getConfig().getBoolean("vanish.potion-invisibility", true);

        if (hide) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.equals(player)) continue;
                if (enable) other.hidePlayer(this, player);
                else other.showPlayer(this, player);
            }
        }

        if (potion) {
            if (enable) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
            } else {
                player.removePotionEffect(PotionEffectType.INVISIBILITY);
            }
        }
    }

    // /gm, /gm s|c|a, /gm <player>, /gm s|c|a <player>
    private boolean cmdGamemode(Player player, String[] args) {
        if (!getConfig().getBoolean("gamemode.enabled", true)) {
            player.sendMessage(colorize(getConfig().getString("prefix", "") + "&cКоманда отключена."));
            return true;
        }

        GameMode desired = null;
        Player target = player;

        if (args.length == 0) {
            // toggle self: survival <-> creative (if something else, go survival)
            desired = (player.getGameMode() == GameMode.SURVIVAL) ? GameMode.CREATIVE : GameMode.SURVIVAL;
        } else if (args.length == 1) {
            // could be mode or player
            GameMode maybeMode = parseMode(args[0]);
            if (maybeMode != null) {
                desired = maybeMode;
            } else {
                target = findPlayerFlexible(args[0]);
                if (target == null) {
                    sendNotFound(player, args[0]);
                    return true;
                }
                // toggle target
                desired = (target.getGameMode() == GameMode.SURVIVAL) ? GameMode.CREATIVE : GameMode.SURVIVAL;
            }
        } else {
            // args[0]=mode OR player, args[1]=player OR mode (we support /gm s nick and /gm nick s)
            GameMode m0 = parseMode(args[0]);
            GameMode m1 = parseMode(args[1]);

            if (m0 != null) {
                desired = m0;
                target = findPlayerFlexible(args[1]);
            } else if (m1 != null) {
                desired = m1;
                target = findPlayerFlexible(args[0]);
            } else {
                // /gm nick otherNick not supported; show usage
                player.sendMessage(colorize(getConfig().getString("prefix","") + "&eИспользуй: /gm [s|c|a] [ник]"));
                return true;
            }

            if (target == null) {
                sendNotFound(player, args[0].equalsIgnoreCase(args[1]) ? args[1] : args[1]);
                return true;
            }
        }

        if (desired == null) {
            player.sendMessage(colorize(getConfig().getString("prefix","") + "&eИспользуй: /gm [s|c|a] [ник]"));
            return true;
        }

        // permissions: changing other players needs extra permission
        boolean changingOther = !target.getUniqueId().equals(player.getUniqueId());
        if (changingOther && !player.hasPermission("ttv.gm.other")) {
            String msg = getConfig().getString("gamemode.no-permission-other", "{prefix}&cNo permission.");
            player.sendMessage(colorize(applyPlaceholders(msg, Map.of("prefix", getConfig().getString("prefix","")))));
            return true;
        }

        target.setGameMode(desired);

        String prefix = getConfig().getString("prefix", "");
        Map<String, String> ph = new HashMap<>();
        ph.put("prefix", prefix);
        ph.put("player", player.getName());
        ph.put("target", target.getName());
        ph.put("mode", desired.name());

        if (changingOther) {
            String changerMsg = getConfig().getString("gamemode.changer-message", "{prefix}&aSet {target} to {mode}");
            player.sendMessage(colorize(applyPlaceholders(changerMsg, ph)));

            String targetMsg = getConfig().getString("gamemode.target-message", "{prefix}&aYour mode is now {mode} by {player}");
            target.sendMessage(colorize(applyPlaceholders(targetMsg, ph)));
        } else {
            String selfMsg = getConfig().getString("gamemode.self-message", "{prefix}&aYour mode is now {mode}");
            player.sendMessage(colorize(applyPlaceholders(selfMsg, ph)));
        }

        return true;
    }

    private void sendNotFound(Player sender, String raw) {
        String prefix = getConfig().getString("prefix", "");
        String msg = getConfig().getString("gamemode.not-found", "{prefix}&cPlayer not found: {target}");
        sender.sendMessage(colorize(applyPlaceholders(msg, Map.of("prefix", prefix, "target", raw))));
    }

    private GameMode parseMode(String in) {
        if (in == null) return null;
        String s = in.trim().toLowerCase(Locale.ROOT);

        return switch (s) {
            case "s", "survival", "0" -> GameMode.SURVIVAL;
            case "c", "creative", "1" -> GameMode.CREATIVE;
            case "a", "adventure", "2" -> GameMode.ADVENTURE;
            // spectator intentionally not exposed (you can add if needed)
            default -> null;
        };
    }

    private Player findPlayerFlexible(String raw) {
        if (raw == null) return null;
        String name = raw.trim();

        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p;

        // case-insensitive online match
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) return online;
        }

        if (getConfig().getBoolean("gamemode.accept-dot-prefix", true)) {
            // ".Nick" -> "Nick"
            if (name.startsWith(".") && name.length() > 1) {
                String stripped = name.substring(1);
                p = Bukkit.getPlayerExact(stripped);
                if (p != null) return p;
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getName().equalsIgnoreCase(stripped)) return online;
                }
            }
            // "Nick" -> try ".Nick" (some servers show dot prefix)
            String dotted = "." + name;
            p = Bukkit.getPlayerExact(dotted);
            if (p != null) return p;
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().equalsIgnoreCase(dotted)) return online;
            }
        }

        return null;
    }

    private boolean cmdClearChat(CommandSender sender) {
        if (!getConfig().getBoolean("clearchat.enabled", true)) {
            sender.sendMessage(colorize(getConfig().getString("prefix", "") + "&cКоманда отключена."));
            return true;
        }

        int lines = Math.max(1, Math.min(500, getConfig().getInt("clearchat.lines", 120)));
        Component empty = Component.empty();
        for (int i = 0; i < lines; i++) {
            Bukkit.broadcast(empty);
        }

        String prefix = getConfig().getString("prefix", "");
        String senderMsg = getConfig().getString("clearchat.sender-message", "{prefix}&aChat cleared.");
        Map<String, String> ph = new HashMap<>();
        ph.put("prefix", prefix);
        ph.put("player", (sender instanceof Player p) ? p.getName() : "Console");

        sender.sendMessage(colorize(applyPlaceholders(senderMsg, ph)));

        if (getConfig().getBoolean("clearchat.broadcast", true)) {
            String bc = getConfig().getString("clearchat.broadcast-message", "{prefix}&e{player}&a cleared chat.");
            Bukkit.broadcast(colorize(applyPlaceholders(bc, ph)));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!name.equals("gm")) return Collections.emptyList();

        if (args.length == 1) {
            return List.of("s", "c", "a", "survival", "creative", "adventure");
        }
        if (args.length == 2) {
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            return out;
        }
        return Collections.emptyList();
    }

    // Utils
    private Component colorize(String legacyText) {
        return legacy().deserialize(legacyText == null ? "" : legacyText);
    }

    private String applyPlaceholders(String text, Map<String, String> placeholders) {
        if (text == null) return "";
        String out = text;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }
}
