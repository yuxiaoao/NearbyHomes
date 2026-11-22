package net.starryisle.nearbyhomes;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads HuskHomes homes and resolves their coordinates.
 *
 * <p>HuskHomes records a home as {@code home -> saved position -> position}. The
 * three tables are read separately and joined in memory rather than in SQL: the
 * table and column names differ between HuskHomes revisions, and an in-memory
 * join lets an unresolvable home be reported instead of silently disappearing.
 */
public final class HomesQuery {

    private HomesQuery() {
    }

    /** A home with a known position. */
    public record Home(String name, String owner, double x, double y, double z,
                       String world, String server, boolean publicHome, double distance) {
    }

    public record Result(List<Home> homes, Set<String> worlds, Home nearest) {
    }

    /**
     * One homes-table row before coordinates are known.
     *
     * @param resolved false when the home exists but its position record is
     *                 missing, which is why such a home never appears in a
     *                 distance listing
     */
    public record RawHome(String uuid, String name, String ownerUuid, Double x, Double y, Double z,
                          String world, String server, boolean publicHome, boolean resolved) {
    }

    private record Position(double x, double y, double z, String world, String server) {
    }

    private record SavedPosition(int positionId, String name) {
    }

    private static final String[] X_COLUMNS = {"x", "pos_x"};
    private static final String[] Y_COLUMNS = {"y", "pos_y"};
    private static final String[] Z_COLUMNS = {"z", "pos_z"};
    private static final String[] WORLD_COLUMNS = {"world_name", "world", "world_id"};
    private static final String[] SERVER_COLUMNS = {"server_name", "server"};
    private static final String[] NAME_COLUMNS = {"name", "home_name"};

    /** Reads every home row, resolving coordinates where possible. */
    public static List<RawHome> rawHomes(Connection connection) throws SQLException {
        String quote = quoteOf(connection);
        Map<String, String> tables = tables(connection);
        String homesTable = locateHomesTable(connection, tables);
        if (homesTable == null) {
            throw new SQLException("找不到 HuskHomes 的家表，现有表: "
                    + String.join(", ", tables.values()));
        }
        Map<String, String> homeColumns = columnsOf(connection, homesTable);

        if (pick(homeColumns, X_COLUMNS) != null) {
            return readDirectHomes(connection, quote, homesTable, homeColumns);
        }

        String positionTable = findTableWith(connection, tables, List.of(homesTable),
                new String[]{"id", "x", "y", "z"}, new String[]{"world_name"});
        if (positionTable == null) {
            throw new SQLException("找不到坐标表（包含 id/x/y/z 的表），现有表: "
                    + String.join(", ", tables.values()));
        }
        String savedTable = findTableWith(connection, tables, List.of(homesTable, positionTable),
                new String[]{"id", "position_id"}, new String[]{"name"});
        if (savedTable == null) {
            throw new SQLException("找不到家的位置表（包含 id/position_id 的表），现有表: "
                    + String.join(", ", tables.values()));
        }

        Map<Integer, Position> positions = readPositions(connection, quote, positionTable);
        Map<Integer, SavedPosition> savedPositions =
                readSavedPositions(connection, quote, savedTable);

        String linkColumn = pick(homeColumns, "saved_position_id", "position_id");
        String ownerColumn = pick(homeColumns, "owner_uuid", "owner");
        String publicColumn = pick(homeColumns, "public", "is_public");
        String uuidColumn = pick(homeColumns, "uuid");
        requireColumns(homesTable, homeColumns, linkColumn);

        String sql = "SELECT " + quote + linkColumn + quote
                + (ownerColumn == null ? ", NULL" : ", " + quote + ownerColumn + quote)
                + (publicColumn == null ? ", NULL" : ", " + quote + publicColumn + quote)
                + (uuidColumn == null ? ", NULL" : ", " + quote + uuidColumn + quote)
                + " FROM " + quote + homesTable + quote;

        List<RawHome> homes = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                int savedId = rows.getInt(1);
                String ownerUuid = rows.getString(2);
                boolean publicHome = readPublic(rows, 3);
                String uuid = rows.getString(4);

                SavedPosition saved = savedPositions.get(savedId);
                Position position = saved == null ? null : positions.get(saved.positionId());
                if (position == null) {
                    homes.add(new RawHome(uuid, null, ownerUuid, null, null, null, null, null,
                            publicHome, false));
                    continue;
                }
                homes.add(new RawHome(uuid, saved.name(), ownerUuid, position.x(), position.y(),
                        position.z(), position.world(), position.server(), publicHome, true));
            }
        }
        return homes;
    }

    private static Map<Integer, Position> readPositions(Connection connection, String quote,
                                                        String table) throws SQLException {
        Map<String, String> columns = columnsOf(connection, table);
        String id = pick(columns, "id");
        String x = pick(columns, X_COLUMNS);
        String y = pick(columns, Y_COLUMNS);
        String z = pick(columns, Z_COLUMNS);
        String world = pick(columns, WORLD_COLUMNS);
        String server = pick(columns, SERVER_COLUMNS);

        requireColumns(table, columns, id, x, y, z);

        String sql = "SELECT " + quote + id + quote + ", " + quote + x + quote + ", "
                + quote + y + quote + ", " + quote + z + quote
                + (world == null ? ", NULL" : ", " + quote + world + quote)
                + (server == null ? ", NULL" : ", " + quote + server + quote)
                + " FROM " + quote + table + quote;

        Map<Integer, Position> positions = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                positions.put(rows.getInt(1), new Position(rows.getDouble(2), rows.getDouble(3),
                        rows.getDouble(4), rows.getString(5), rows.getString(6)));
            }
        } catch (SQLException exception) {
            throw new SQLException("读取坐标表失败: " + sql + " -> " + exception.getMessage(), exception);
        }
        return positions;
    }

    private static Map<Integer, SavedPosition> readSavedPositions(Connection connection,
                                                                 String quote, String table)
            throws SQLException {
        Map<String, String> columns = columnsOf(connection, table);
        String id = pick(columns, "id");
        String positionId = pick(columns, "position_id");
        String name = pick(columns, NAME_COLUMNS);

        requireColumns(table, columns, id, positionId);

        String sql = "SELECT " + quote + id + quote + ", " + quote + positionId + quote
                + (name == null ? ", NULL" : ", " + quote + name + quote)
                + " FROM " + quote + table + quote;

        Map<Integer, SavedPosition> saved = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                saved.put(rows.getInt(1), new SavedPosition(rows.getInt(2), rows.getString(3)));
            }
        } catch (SQLException exception) {
            throw new SQLException("读取位置表失败: " + sql + " -> " + exception.getMessage(), exception);
        }
        return saved;
    }

    /** Fails loudly instead of building a statement with a literal {@code null}. */
    private static void requireColumns(String table, Map<String, String> columns,
                                       String... required) throws SQLException {
        for (String column : required) {
            if (column == null) {
                throw new SQLException("表 " + table + " 缺少必需的列，实际列: "
                        + String.join(", ", columns.values()));
            }
        }
    }

    /** Legacy layout: the coordinates sit on the home row itself. */
    private static List<RawHome> readDirectHomes(Connection connection, String quote, String table,
                                                 Map<String, String> columns) throws SQLException {
        String x = pick(columns, X_COLUMNS);
        String y = pick(columns, Y_COLUMNS);
        String z = pick(columns, Z_COLUMNS);
        String name = pick(columns, NAME_COLUMNS);
        String owner = pick(columns, "owner_uuid", "owner");
        String world = pick(columns, WORLD_COLUMNS);
        String server = pick(columns, SERVER_COLUMNS);
        String publicColumn = pick(columns, "public", "is_public");
        String uuid = pick(columns, "uuid");

        String sql = "SELECT " + quote + x + quote + ", " + quote + y + quote + ", "
                + quote + z + quote
                + (name == null ? ", NULL" : ", " + quote + name + quote)
                + (owner == null ? ", NULL" : ", " + quote + owner + quote)
                + (world == null ? ", NULL" : ", " + quote + world + quote)
                + (server == null ? ", NULL" : ", " + quote + server + quote)
                + (publicColumn == null ? ", NULL" : ", " + quote + publicColumn + quote)
                + (uuid == null ? ", NULL" : ", " + quote + uuid + quote)
                + " FROM " + quote + table + quote;

        List<RawHome> homes = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                double px = rows.getDouble(1);
                double py = rows.getDouble(2);
                double pz = rows.getDouble(3);
                boolean resolved = !rows.wasNull();
                boolean publicHome = readPublic(rows, 8);
                String uuidValue = rows.getString(9);
                homes.add(new RawHome(uuidValue, rows.getString(4), rows.getString(5),
                        resolved ? px : null, resolved ? py : null, resolved ? pz : null,
                        rows.getString(6), rows.getString(7), publicHome, resolved));
            }
        }
        return homes;
    }

    public static Result findNearby(Connection connection, double centerX, double centerZ,
                                    double radius, String worldFilter, String serverFilter,
                                    int limit) throws SQLException {
        List<RawHome> raw = rawHomes(connection);
        Map<String, String> owners = playerNames(connection, tables(connection), quoteOf(connection));

        List<Home> everyHome = new ArrayList<>();
        Set<String> worlds = new LinkedHashSet<>();
        for (RawHome home : raw) {
            if (!home.resolved()) {
                continue;
            }
            if (home.world() != null) {
                worlds.add(home.world());
            }
            if (worldFilter != null && !worldFilter.isBlank()
                    && (home.world() == null || !home.world().equalsIgnoreCase(worldFilter))) {
                continue;
            }
            if (serverFilter != null && !serverFilter.isBlank()
                    && (home.server() == null || !home.server().equalsIgnoreCase(serverFilter))) {
                continue;
            }
            double distance = Math.hypot(home.x() - centerX, home.z() - centerZ);
            everyHome.add(new Home(
                    home.name() == null || home.name().isBlank() ? "(未命名)" : home.name(),
                    displayOwner(home.ownerUuid(), owners),
                    home.x(), home.y(), home.z(),
                    home.world() == null ? "?" : home.world(),
                    home.server() == null ? "" : home.server(),
                    home.publicHome(), distance));
        }

        everyHome.sort(Comparator.comparingDouble(Home::distance));
        Home nearest = everyHome.isEmpty() ? null : everyHome.get(0);

        List<Home> inside = new ArrayList<>();
        for (Home home : everyHome) {
            if (home.distance() <= radius) {
                inside.add(home);
            }
        }
        if (inside.size() > limit) {
            inside = new ArrayList<>(inside.subList(0, limit));
        }
        return new Result(inside, worlds, nearest);
    }

    /** Every home of one player, including rows whose coordinates are missing. */
    public static List<RawHome> homesOf(Connection connection, String nameOrUuid) throws SQLException {
        String quote = quoteOf(connection);
        Map<String, String> tables = tables(connection);
        Map<String, String> owners = playerNames(connection, tables, quote);
        String wanted = nameOrUuid == null ? "" : nameOrUuid.trim();
        String uuid = resolvePlayerUuid(connection, tables, quote, wanted);

        List<RawHome> owned = new ArrayList<>();
        for (RawHome home : rawHomes(connection)) {
            if (home.ownerUuid() == null) {
                continue;
            }
            boolean matchUuid = uuid != null && home.ownerUuid().equalsIgnoreCase(uuid);
            String ownerName = owners.getOrDefault(home.ownerUuid().toLowerCase(Locale.ROOT), "");
            if (matchUuid || ownerName.equalsIgnoreCase(wanted)) {
                owned.add(home);
            }
        }
        return owned;
    }

    /** Diagnostic report for {@code /nearbyhomes debug}. */
    public static List<String> diagnose(Connection connection) throws SQLException {
        String quote = quoteOf(connection);
        List<String> lines = new ArrayList<>();
        Map<String, String> tables = tables(connection);

        // Other plugins share the schema; only HuskHomes tables are interesting.
        List<String> huskTables = new ArrayList<>();
        for (String table : tables.values()) {
            if (table.toLowerCase(Locale.ROOT).contains("husk")) {
                huskTables.add(table);
            }
        }
        List<String> listed = huskTables.isEmpty()
                ? new ArrayList<>(tables.values())
                : huskTables;
        lines.add("表（HuskHomes）: " + String.join(", ", listed));

        for (String table : listed) {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT COUNT(*) FROM " + quote + table + quote)) {
                if (rows.next()) {
                    lines.add("  " + table + " = " + rows.getLong(1) + " 行");
                }
            } catch (SQLException exception) {
                lines.add("  " + table + " 读取失败: " + exception.getMessage());
            }
        }

        List<RawHome> raw;
        try {
            raw = rawHomes(connection);
        } catch (SQLException exception) {
            lines.add("读取家表失败: " + exception.getMessage());
            return lines;
        }

        int resolved = 0;
        Map<String, Integer> perWorld = new HashMap<>();
        Set<String> worlds = new LinkedHashSet<>();
        List<String> unresolved = new ArrayList<>();
        for (RawHome home : raw) {
            if (!home.resolved()) {
                if (unresolved.size() < 5) {
                    unresolved.add(home.uuid() == null ? "?" : home.uuid());
                }
                continue;
            }
            resolved++;
            if (home.world() != null) {
                worlds.add(home.world());
                perWorld.merge(home.world(), 1, Integer::sum);
            }
        }

        lines.add("家总数: " + raw.size() + "，能解析坐标: " + resolved
                + "，解析不到坐标: " + (raw.size() - resolved));
        for (Map.Entry<String, Integer> entry : perWorld.entrySet()) {
            lines.add("  世界 " + entry.getKey() + " : " + entry.getValue() + " 个家");
        }
        if (!unresolved.isEmpty()) {
            lines.add("  无坐标的家（uuid）: " + String.join(", ", unresolved));
        }
        if (!worlds.isEmpty()) {
            lines.add("世界列表: " + String.join(", ", worlds));
        }
        return lines;
    }

    private static String displayOwner(String ownerUuid, Map<String, String> owners) {
        if (ownerUuid == null) {
            return "?";
        }
        String name = owners.get(ownerUuid.toLowerCase(Locale.ROOT));
        if (name != null) {
            return name;
        }
        return ownerUuid.length() > 8 ? ownerUuid.substring(0, 8) : ownerUuid;
    }

    private static String resolvePlayerUuid(Connection connection, Map<String, String> tables,
                                            String quote, String nameOrUuid) {
        String wanted = nameOrUuid == null ? "" : nameOrUuid.trim();
        for (String table : tables.values()) {
            try {
                Map<String, String> columns = columnsOf(connection, table);
                String uuidColumn = pick(columns, "uuid", "user_uuid");
                String nameColumn = pick(columns, "username", "user_name");
                if (uuidColumn == null || nameColumn == null || pick(columns, X_COLUMNS) != null) {
                    continue;
                }
                String sql = "SELECT " + quote + uuidColumn + quote + " FROM " + quote + table
                        + quote + " WHERE LOWER(" + quote + nameColumn + quote + ") = LOWER(?)"
                        + " OR LOWER(" + quote + uuidColumn + quote + ") = LOWER(?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, wanted);
                    statement.setString(2, wanted);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (rows.next()) {
                            return rows.getString(1);
                        }
                    }
                }
            } catch (SQLException ignored) {
                // Try the next candidate table.
            }
        }
        return wanted.isBlank() ? null : wanted;
    }

    private static Map<String, String> playerNames(Connection connection,
                                                   Map<String, String> tables, String quote) {
        Map<String, String> names = new HashMap<>();
        for (String table : tables.values()) {
            try {
                Map<String, String> columns = columnsOf(connection, table);
                String uuidColumn = pick(columns, "uuid", "user_uuid");
                String nameColumn = pick(columns, "username", "user_name");
                if (uuidColumn == null || nameColumn == null || pick(columns, X_COLUMNS) != null) {
                    continue;
                }
                String sql = "SELECT " + quote + uuidColumn + quote + ", "
                        + quote + nameColumn + quote + " FROM " + quote + table + quote;
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery(sql)) {
                    while (rows.next()) {
                        String uuid = rows.getString(1);
                        String name = rows.getString(2);
                        if (uuid != null && name != null && !name.isBlank()) {
                            names.put(uuid.toLowerCase(Locale.ROOT), name);
                        }
                    }
                }
            } catch (SQLException ignored) {
                // Owner names are cosmetic.
            }
        }
        return names;
    }

    private static String locateHomesTable(Connection connection, Map<String, String> tables) {
        for (String name : tables.values()) {
            if (name.equalsIgnoreCase("huskhomes_homes") || name.equalsIgnoreCase("homes")) {
                return name;
            }
        }
        for (String name : tables.values()) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith("homes") && lower.contains("husk")) {
                return name;
            }
        }
        for (String name : tables.values()) {
            try {
                Map<String, String> columns = columnsOf(connection, name);
                boolean looksLikeHomes = pick(columns, "owner_uuid") != null
                        && pick(columns, "saved_position_id", "position_id") != null;
                if (looksLikeHomes) {
                    return name;
                }
            } catch (SQLException ignored) {
                // Next candidate.
            }
        }
        return null;
    }

    /**
     * Finds a table holding every required column.
     *
     * <p>Other plugins share the schema, so tables whose name contains
     * {@code huskhomes} are tried first; a table that merely happens to have an
     * {@code id} column must never be mistaken for a HuskHomes table.
     */
    private static String findTableWith(Connection connection, Map<String, String> tables,
                                        List<String> excluded, String[] required,
                                        String[] preferred) throws SQLException {
        List<String> candidates = new ArrayList<>(tables.values());
        candidates.sort(Comparator.comparingInt((String name) ->
                name.toLowerCase(Locale.ROOT).contains("huskhomes") ? 0 : 1));

        for (String candidate : candidates) {
            if (excluded.stream().anyMatch(name -> name.equalsIgnoreCase(candidate))) {
                continue;
            }
            Map<String, String> columns = columnsOf(connection, candidate);
            boolean hasAll = true;
            for (String name : required) {
                if (name != null && pick(columns, name) == null) {
                    hasAll = false;
                    break;
                }
            }
            if (!hasAll) {
                continue;
            }
            for (String wanted : preferred) {
                if (pick(columns, wanted) != null) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static Map<String, String> tables(Connection connection) throws SQLException {
        Map<String, String> tables = new HashMap<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rows = metadata.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rows.next()) {
                String name = rows.getString("TABLE_NAME");
                if (name != null) {
                    tables.put(name.toLowerCase(Locale.ROOT), name);
                }
            }
        }
        return tables;
    }

    private static Map<String, String> columnsOf(Connection connection, String table)
            throws SQLException {
        Map<String, String> columns = new HashMap<>();
        try (ResultSet rows = connection.getMetaData().getColumns(null, null, table, "%")) {
            while (rows.next()) {
                String name = rows.getString("COLUMN_NAME");
                if (name != null) {
                    columns.put(name.toLowerCase(Locale.ROOT), name);
                }
            }
        }
        if (columns.isEmpty()) {
            String quote = quoteOf(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT * FROM " + quote + table + quote + " WHERE 1 = 0")) {
                for (int index = 1; index <= rows.getMetaData().getColumnCount(); index++) {
                    String name = rows.getMetaData().getColumnName(index);
                    columns.put(name.toLowerCase(Locale.ROOT), name);
                }
            }
        }
        return columns;
    }

    private static boolean readPublic(ResultSet rows, int index) throws SQLException {
        Object value = rows.getObject(index);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value.toString().equalsIgnoreCase("true") || value.toString().equals("1");
    }

    private static String pick(Map<String, String> columns, String... candidates) {
        for (String candidate : candidates) {
            String match = columns.get(candidate);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static String quoteOf(Connection connection) {
        try {
            String quote = connection.getMetaData().getIdentifierQuoteString();
            return quote == null || quote.isBlank() ? "" : quote;
        } catch (SQLException exception) {
            return "";
        }
    }
}
