package net.starryisle.nearbyhomes;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Resolves where the HuskHomes database lives and how to connect to it.
 *
 * <p>When the plugin's own {@code database.type} is {@code auto} the HuskHomes
 * {@code config.yml} is read instead, so the connection follows whatever the
 * server already configured.
 */
public final class DatabaseSettings {

    private final String driver;
    private final String url;
    private final String username;
    private final String password;
    private final String description;

    private DatabaseSettings(String driver, String url, String username, String password,
                             String description) {
        this.driver = driver;
        this.url = url;
        this.username = username;
        this.password = password;
        this.description = description;
    }

    public String describe() {
        return description;
    }

    public Connection open() throws SQLException {
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException exception) {
            throw new SQLException("缺少数据库驱动: " + driver, exception);
        }
        return DriverManager.getConnection(url, username, password);
    }

    public static DatabaseSettings resolve(JavaPlugin plugin) {
        String type = plugin.getConfig().getString("database.type", "auto");
        if (type == null || type.isBlank() || type.equalsIgnoreCase("auto")) {
            return fromHuskHomes(plugin);
        }
        return fromOwnConfig(plugin, type);
    }

    private static DatabaseSettings fromHuskHomes(JavaPlugin plugin) {
        return fromHuskHomesConfig(
                new File(plugin.getDataFolder().getParentFile(), "HuskHomes"));
    }

    /**
     * Builds the connection settings from a HuskHomes data folder.
     *
     * <p>HuskHomes keeps remote credentials under {@code database.credentials.*};
     * older builds nested them under {@code database.mysql.*}, so both layouts are
     * accepted.
     */
    static DatabaseSettings fromHuskHomesConfig(File huskHomesFolder) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new File(huskHomesFolder, "config.yml"));

        String type = firstNonBlank(config.getString("database.type"), "SQLITE")
                .toUpperCase(Locale.ROOT);

        return switch (type) {
            case "MYSQL", "MARIADB" -> remote(config, huskHomesFolder, false);
            case "POSTGRESQL", "POSTGRES" -> remote(config, huskHomesFolder, true);
            case "H2" -> h2(config, huskHomesFolder);
            default -> sqlite(huskHomesFolder,
                    config.getString("database.file", "data.db"));
        };
    }

    /** First non-blank value across the accepted credential layouts. */
    private static String credential(YamlConfiguration config, String key, String fallback) {
        for (String prefix : new String[]{
                "database.credentials.", "database.mysql.", "database.postgresql.", "database."}) {
            String value = config.getString(prefix + key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    private static DatabaseSettings fromOwnConfig(JavaPlugin plugin, String type) {
        String normalized = type.toUpperCase(Locale.ROOT);
        if (normalized.equals("MYSQL") || normalized.equals("MARIADB")
                || normalized.equals("POSTGRESQL") || normalized.equals("POSTGRES")) {
            boolean postgres = normalized.startsWith("POST");
            String rawHost = plugin.getConfig().getString("database.host", "");
            String rawName = plugin.getConfig().getString("database.name", "");
            String rawUser = plugin.getConfig().getString("database.username", "");
            String password = plugin.getConfig().getString("database.password", "");

            warnAboutBlankFields(plugin, postgres, rawHost, rawName, rawUser, password);

            String host = firstNonBlank(rawHost, "localhost");
            int port = plugin.getConfig().getInt("database.port", postgres ? 5432 : 3306);
            String name = firstNonBlank(rawName, "huskhomes");
            String user = firstNonBlank(rawUser, postgres ? "postgres" : "root");

            String driver = postgres ? "org.postgresql.Driver" : "com.mysql.cj.jdbc.Driver";
            String scheme = postgres ? "postgresql" : "mysql";
            String defaults = postgres
                    ? "?sslmode=disable"
                    : "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";

            return new DatabaseSettings(
                    driver,
                    "jdbc:" + scheme + "://" + host + ":" + port + "/" + name + defaults,
                    user,
                    password,
                    scheme + " " + user + "@" + host + ":" + port + "/" + name);
        }

        File huskHomesFolder = new File(plugin.getDataFolder().getParentFile(), "HuskHomes");
        String file = firstNonBlank(plugin.getConfig().getString("database.file"), "data.db");
        return sqlite(huskHomesFolder, file);
    }

    /**
     * Warns about the mistakes that otherwise degrade silently into a default
     * connection: a blank field falls back to a guess, and a username that equals
     * the password means the two config keys were filled in the wrong order.
     */
    private static void warnAboutBlankFields(JavaPlugin plugin, boolean postgres, String host,
                                              String name, String user, String password) {
        String label = postgres ? "PostgreSQL" : "MySQL";
        if (host == null || host.isBlank()) {
            plugin.getLogger().warning(
                    "database.host 为空，正在使用 localhost；请在 plugins/NearbyHomes/config.yml "
                            + "填写 " + label + " 的主机地址");
        }
        if (name == null || name.isBlank()) {
            plugin.getLogger().warning(
                    "database.name 为空，正在使用 huskhomes；请填写 HuskHomes 使用的数据库名");
        }
        if (user == null || user.isBlank()) {
            plugin.getLogger().warning(
                    "database.username 为空，正在使用默认账号；请填写 " + label + " 的用户名");
        } else if (password != null && !password.isBlank() && user.equals(password)) {
            plugin.getLogger().warning(
                    "database.username 与 database.password 相同（" + user + "），很可能是填反了："
                            + "username 应填账号名，password 才填密码");
        }
    }

    private static DatabaseSettings sqlite(File huskHomesFolder, String file) {
        File database = new File(file);
        if (!database.isAbsolute()) {
            database = new File(huskHomesFolder, file);
        }
        return new DatabaseSettings(
                "org.sqlite.JDBC",
                "jdbc:sqlite:" + database.getAbsolutePath(),
                null,
                null,
                "sqlite " + database.getAbsolutePath());
    }

    private static DatabaseSettings remote(YamlConfiguration config, File huskHomesFolder,
                                           boolean postgres) {
        String host = credential(config, "host", "localhost");
        int port = Integer.parseInt(credential(config, "port", postgres ? "5432" : "3306"));
        String name = credential(config, "database", "huskhomes");
        String user = credential(config, "username", postgres ? "postgres" : "root");
        String password = credential(config, "password", "");
        String params = credential(config, "params", credential(config, "parameters", ""));

        String driver = postgres ? "org.postgresql.Driver" : "com.mysql.cj.jdbc.Driver";
        String scheme = postgres ? "postgresql" : "mysql";
        String defaults = postgres
                ? "?sslmode=disable"
                : "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
        String query = params.isBlank() ? defaults : "?" + params;

        return new DatabaseSettings(
                driver,
                "jdbc:" + scheme + "://" + host + ":" + port + "/" + name + query,
                user,
                password,
                scheme + " " + user + "@" + host + ":" + port + "/" + name);
    }

    private static DatabaseSettings h2(YamlConfiguration config, File huskHomesFolder) {
        String file = config.getString("database.file", "data");
        File database = new File(file);
        if (!database.isAbsolute()) {
            database = new File(huskHomesFolder, file);
        }
        return new DatabaseSettings(
                "org.h2.Driver",
                "jdbc:h2:" + database.getAbsolutePath(),
                null,
                null,
                "h2 " + database.getAbsolutePath());
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
