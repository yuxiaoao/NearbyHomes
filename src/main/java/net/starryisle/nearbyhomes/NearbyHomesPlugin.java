package net.starryisle.nearbyhomes;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** Plugin entry point: resolves the database once and registers the command. */
public final class NearbyHomesPlugin extends JavaPlugin {

    /** Printed on enable so a stale jar in {@code plugins/} is easy to spot. */
    private static final String BUILD = "2026-11-21.2";

    private DatabaseSettings database;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        database = DatabaseSettings.resolve(this);

        PluginCommand command = getCommand("nearbyhomes");
        if (command == null) {
            getLogger().severe("nearbyhomes is missing from plugin.yml");
            return;
        }
        NearbyCommand executor = new NearbyCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        getLogger().info("NearbyHomes build " + BUILD + " enabled, using " + database.describe());
    }

    public DatabaseSettings database() {
        return database;
    }
}
