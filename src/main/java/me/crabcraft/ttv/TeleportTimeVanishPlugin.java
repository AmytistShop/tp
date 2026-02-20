package me.crabcraft.ttv;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
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
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("day")) {
            if (!getConfig().getBoolean("time.enabled", true)) {
                player.sendMessage(colorize(getConfig().getString("prefix", "") + "&cКоманда отключена."));
                return true;
            }
            setTime(player, "day", getConfig().getLong("time.day-ticks", 1000));
            return true;
        }

        if (name.equals("night")) {
            if (!getConfig().getBoolean("time.enabled", true)) {
                player.sendMessage(colorize(getConfig().getString("prefix", "") + "&cКоманда отключена."));
                return true;
            }
            setTime(player, "night", getConfig().getLong("time.night-ticks", 13000));
            return true;
        }

        if (name.equals("vanish")) {
            if (!getConfig().getBoolean("vanish.enabled", true)) {
                player.sendMessage(colorize(getConfig().getString("prefix", "") + "&cКоманда отключена."));
                return true;
            }
            toggleVanish(player);
            return true;
        }

        return false;
    }

    private void setTime(Player player, String timeKey, long ticks) {
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
    }

    private void toggleVanish(Player player) {
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
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
