package net.starryisle.nearbyhomes;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Handles {@code /nearbyhomes [radius|all|debug] [world]}. */
public final class NearbyCommand implements CommandExecutor, TabCompleter {

    /**
     * Built-in copy, used whenever the server's {@code config.yml} predates a
     * message key. Without this an old config silently swallows the newer hints,
     * which looks like the command found nothing to say.
     */
    private static final Map<String, String> DEFAULT_MESSAGES = Map.ofEntries(
            Map.entry("no-permission", "&c你没有权限使用这个命令。"),
            Map.entry("usage", "&e用法：/nearbyhomes [半径|all|debug] [世界]"),
            Map.entry("bad-radius", "&c半径必须是 1 到 {max} 之间的数字。"),
            Map.entry("querying", "&7正在查询 {radius} …"),
            Map.entry("empty", "&7{radius}内没有找到任何家。"),
            Map.entry("nearest",
                    "&7最近的一个在 &e{distance} 格&7外：&f{home} &7({owner}) &8[{x}, {y}, {z}] &7{world}"),
            Map.entry("tip", "&7想查更远可以加半径，比如 &e/nearbyhomes 500"),
            Map.entry("header", "&6{radius}内找到 {count} 个家："),
            Map.entry("line",
                    "&e{distance} 格 &f{home} &7({owner}) &8[{x}, {y}, {z}] &7{world}{public}"),
            Map.entry("worlds", "&7数据库里的世界：{worlds}"),
            Map.entry("failed", "&c查询失败：{error}"),
            Map.entry("access-denied",
                    "&c数据库登录被拒绝：{error}&7。请检查 plugins/HuskHomes/config.yml 的 "
                            + "database.credentials，或在 plugins/NearbyHomes/config.yml 手动填写账号密码。"));

    private final NearbyHomesPlugin plugin;

    public NearbyCommand(NearbyHomesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("nearbyhomes.use")) {
            send(sender, "no-permission");
            return true;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "usage");
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("player")) {
            String target = args[1];
            double originX = player.getLocation().getX();
            double originZ = player.getLocation().getZ();
            send(player, "querying", "{radius}", target);
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                List<String> report = new ArrayList<>();
                try (Connection connection = plugin.database().open()) {
                    List<HomesQuery.RawHome> homes = HomesQuery.homesOf(connection, target);
                    if (homes.isEmpty()) {
                        report.add("没有找到玩家 " + target + " 的家。");
                    } else {
                        report.add(target + " 共有 " + homes.size() + " 个家（按离你的距离排序）：");
                        for (HomesQuery.RawHome home : homes) {
                            if (!home.resolved()) {
                                report.add("  ? 格 " + (home.name() == null ? "(未知)" : home.name())
                                        + "  —— 数据库里没有坐标记录（孤儿）");
                                continue;
                            }
                            double distance = Math.hypot(home.x() - originX, home.z() - originZ);
                            report.add(String.format(Locale.ROOT,
                                    "  %.0f 格 %s  [%d, %d, %d] %s%s%s",
                                    distance, home.name(),
                                    (int) Math.floor(home.x()), (int) Math.floor(home.y()),
                                    (int) Math.floor(home.z()), home.world(),
                                    home.server() == null ? "" : " @ " + home.server(),
                                    home.publicHome() ? " [公开]" : ""));
                        }
                    }
                } catch (SQLException exception) {
                    report.add("查询失败: " + exception.getMessage());
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    for (String line : report) {
                        player.sendMessage(ChatColor.GRAY + line);
                    }
                });
            });
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("debug")) {
            send(player, "querying", "{radius}", "诊断");
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                List<String> report = new ArrayList<>();
                try (Connection connection = plugin.database().open()) {
                    report.addAll(HomesQuery.diagnose(connection));
                    report.add("你的位置: " + player.getWorld().getName() + " "
                            + (int) player.getLocation().getX() + ", "
                            + (int) player.getLocation().getY() + ", "
                            + (int) player.getLocation().getZ());
                } catch (SQLException exception) {
                    report.add("诊断失败: " + exception.getMessage());
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    for (String line : report) {
                        player.sendMessage(ChatColor.GRAY + line);
                    }
                });
            });
            return true;
        }

        int maxRadius = plugin.getConfig().getInt("max-radius", 500);
        double radius = plugin.getConfig().getDouble("default-radius", 100);
        boolean unlimited = false;

        if (args.length >= 1) {
            if (args[0].equalsIgnoreCase("all")) {
                unlimited = true;
                radius = Double.MAX_VALUE;
            } else {
                try {
                    radius = Double.parseDouble(args[0]);
                } catch (NumberFormatException exception) {
                    send(sender, "bad-radius");
                    return true;
                }
            }
        }
        if (!unlimited && (radius <= 0 || radius > maxRadius)) {
            send(sender, "bad-radius");
            return true;
        }

        String worldName = args.length >= 2 ? args[1] : player.getWorld().getName();
        String serverFilter = plugin.getConfig().getString("database.server", "");
        int limit = plugin.getConfig().getInt("max-results", 30);
        double centerX = player.getLocation().getX();
        double centerZ = player.getLocation().getZ();
        final double queryRadius = radius;
        final boolean unlimitedMode = unlimited;

        send(player, "querying", "{radius}", unlimitedMode ? "全部" : format(queryRadius));

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection connection = plugin.database().open()) {
                HomesQuery.Result result = HomesQuery.findNearby(
                        connection, centerX, centerZ, queryRadius, worldName, serverFilter, limit);
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> render(player, result, queryRadius, worldName, unlimitedMode));
            } catch (SQLException exception) {
                plugin.getLogger().warning("nearby homes query failed: " + exception.getMessage());
                String detail = String.valueOf(exception.getMessage());
                String hintKey = looksLikeAuthFailure(detail) ? "access-denied" : "failed";
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> send(player, hintKey, "{error}", detail));
            }
        });

        return true;
    }

    private void render(Player player, HomesQuery.Result result, double radius, String worldName,
                            boolean unlimited) {
        String radiusLabel = unlimited ? "全部" : format(radius);
        if (result.homes().isEmpty()) {
            send(player, "empty", "{radius}", radiusLabel);
            HomesQuery.Home nearest = result.nearest();
            if (nearest != null) {
                send(player, "nearest",
                        "{distance}", String.format(Locale.ROOT, "%.0f", nearest.distance()),
                        "{home}", nearest.name(),
                        "{owner}", nearest.owner(),
                        "{x}", String.valueOf((int) Math.floor(nearest.x())),
                        "{y}", String.valueOf((int) Math.floor(nearest.y())),
                        "{z}", String.valueOf((int) Math.floor(nearest.z())),
                        "{world}", nearest.world());
            } else if (!result.worlds().isEmpty()) {
                send(player, "worlds", "{worlds}", String.join(", ", result.worlds()));
            }
            send(player, "tip");
            return;
        }

        send(player, "header",
                "{radius}", radiusLabel,
                "{count}", String.valueOf(result.homes().size()));

        String publicTag = color(plugin.getConfig().getString("public-tag", " &a[公开]"));
        for (HomesQuery.Home home : result.homes()) {
            send(player, "line",
                    "{distance}", String.format(Locale.ROOT, "%.1f", home.distance()),
                    "{home}", home.name(),
                    "{owner}", home.owner(),
                    "{x}", String.valueOf((int) Math.floor(home.x())),
                    "{y}", String.valueOf((int) Math.floor(home.y())),
                    "{z}", String.valueOf((int) Math.floor(home.z())),
                    "{world}", home.world(),
                    "{public}", home.publicHome() ? publicTag : "");
        }
    }

    private void send(CommandSender sender, String key, String... placeholders) {
        String message = plugin.getConfig().getString("messages." + key);
        if (message == null || message.isBlank()) {
            message = DEFAULT_MESSAGES.get(key);
        }
        if (message == null || message.isBlank()) {
            return;
        }
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            message = message.replace(placeholders[index], placeholders[index + 1]);
        }
        sender.sendMessage(color(message));
    }

    private static String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message == null ? "" : message);
    }

    private static boolean looksLikeAuthFailure(String detail) {
        String lower = detail.toLowerCase(Locale.ROOT);
        return lower.contains("access denied")
                || lower.contains("password")
                || lower.contains("authentication")
                || lower.contains("login failed");
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            for (String candidate : new String[]{"50", "100", "200", "500", "all"}) {
                if (candidate.startsWith(args[0])) {
                    suggestions.add(candidate);
                }
            }
            return suggestions;
        }
        if (args.length == 2) {
            for (World world : Bukkit.getWorlds()) {
                if (world.getName().toLowerCase(Locale.ROOT)
                        .startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    suggestions.add(world.getName());
                }
            }
        }
        return suggestions;
    }
}
