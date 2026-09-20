/*
 *  This file is part of Player Analytics (Plan).
 *
 *  Plan is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License v3 as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Plan is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with Plan. If not, see <https://www.gnu.org/licenses/>.
 */
package com.djrapitops.plan.storage.database.queries.objects.playertable;

import com.djrapitops.plan.delivery.domain.TablePlayer;
import com.djrapitops.plan.delivery.domain.mutators.ActivityIndex;
import com.djrapitops.plan.gathering.domain.Ping;
import com.djrapitops.plan.identification.ServerUUID;
import com.djrapitops.plan.storage.database.SQLDB;
import com.djrapitops.plan.storage.database.queries.Query;
import com.djrapitops.plan.storage.database.queries.QueryStatement;
import com.djrapitops.plan.storage.database.sql.tables.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.djrapitops.plan.storage.database.sql.building.Sql.*;

/**
 * Query for displaying players on /server page players tab.
 *
 * @author AuroraLS3
 */
public class ServerTablePlayersQuery implements Query<List<TablePlayer>> {

    private final ServerUUID serverUUID;
    private final long date;
    private final long activeMsThreshold;
    private final int xMostRecentPlayers;

    /**
     * Create a new query.
     *
     * @param serverUUID         UUID of the Plan server.
     * @param date               Date used for Activity Index calculation
     * @param activeMsThreshold  Playtime threshold for Activity Index calculation
     * @param xMostRecentPlayers Limit query size
     */
    public ServerTablePlayersQuery(ServerUUID serverUUID, long date, long activeMsThreshold, int xMostRecentPlayers) {
        this.serverUUID = serverUUID;
        this.date = date;
        this.activeMsThreshold = activeMsThreshold;
        this.xMostRecentPlayers = xMostRecentPlayers;
    }

    @Override
    public List<TablePlayer> executeQuery(SQLDB db) {
        long week = TimeUnit.DAYS.toMillis(7L);
        String sessionDuration = "s." + SessionsTable.SESSION_END
                + "-s." + SessionsTable.SESSION_START
                + "-s." + SessionsTable.AFK_TIME;
        String serverId = "(SELECT server_id FROM server_context)";
        String weeklyActivity = "COALESCE(sm.week_%d,0)*1.0/ap.threshold";
        String activityTerm = "1.0/(ap.pi/2.0*(%s)+1.0)";
        String activityIndex = "5.0-5.0*(("
                + String.format(activityTerm, String.format(weeklyActivity, 1)) + '+'
                + String.format(activityTerm, String.format(weeklyActivity, 2)) + '+'
                + String.format(activityTerm, String.format(weeklyActivity, 3))
                + ")/3.0)";

        String sql = "WITH server_context AS (" +
                SELECT + ServerTable.ID + " AS server_id" +
                FROM + ServerTable.TABLE_NAME +
                WHERE + ServerTable.SERVER_UUID + "=?" +
                LIMIT + '1' +
                "), session_last_seen AS (" +
                SELECT + "s." + SessionsTable.USER_ID + ',' +
                max("s." + SessionsTable.SESSION_END) + " AS last_seen" +
                FROM + SessionsTable.TABLE_NAME + " s" +
                WHERE + "s." + SessionsTable.SERVER_ID + '=' + serverId +
                GROUP_BY + "s." + SessionsTable.USER_ID +
                "), recent_players AS (" +
                SELECT + "ui." + UserInfoTable.USER_ID + ',' +
                "ui." + UserInfoTable.BANNED + ',' +
                "sls.last_seen" +
                FROM + UserInfoTable.TABLE_NAME + " ui" +
                LEFT_JOIN + "session_last_seen sls ON sls." + SessionsTable.USER_ID + "=ui." + UserInfoTable.USER_ID +
                WHERE + "ui." + UserInfoTable.SERVER_ID + '=' + serverId +
                ORDER_BY + "sls.last_seen DESC" +
                LIMIT + '?' +
                "), session_metrics AS (" +
                SELECT + "s." + SessionsTable.USER_ID + ',' +
                "COUNT(1) AS count," +
                sum(sessionDuration) + " AS active_playtime," +
                sum("CASE WHEN s." + SessionsTable.SESSION_END + ">=? AND s." + SessionsTable.SESSION_START + "<=? THEN " + sessionDuration + " ELSE 0 END") + " AS week_1," +
                sum("CASE WHEN s." + SessionsTable.SESSION_END + ">=? AND s." + SessionsTable.SESSION_START + "<=? THEN " + sessionDuration + " ELSE 0 END") + " AS week_2," +
                sum("CASE WHEN s." + SessionsTable.SESSION_END + ">=? AND s." + SessionsTable.SESSION_START + "<=? THEN " + sessionDuration + " ELSE 0 END") + " AS week_3" +
                FROM + SessionsTable.TABLE_NAME + " s" +
                INNER_JOIN + "recent_players rp ON rp." + UserInfoTable.USER_ID + "=s." + SessionsTable.USER_ID +
                WHERE + "s." + SessionsTable.SERVER_ID + '=' + serverId +
                GROUP_BY + "s." + SessionsTable.USER_ID +
                "), activity_parameters AS (" +
                SELECT + "? AS pi,? AS threshold" +
                "), ping_data AS (" +
                SELECT + "p." + PingTable.USER_ID + ',' +
                avg("p." + PingTable.AVG_PING) + " AS " + PingTable.AVG_PING + ',' +
                max("p." + PingTable.MAX_PING) + " AS " + PingTable.MAX_PING + ',' +
                min("p." + PingTable.MIN_PING) + " AS " + PingTable.MIN_PING +
                FROM + PingTable.TABLE_NAME + " p" +
                INNER_JOIN + "recent_players rp ON rp." + UserInfoTable.USER_ID + "=p." + PingTable.USER_ID +
                WHERE + "p." + PingTable.SERVER_ID + '=' + serverId +
                GROUP_BY + "p." + PingTable.USER_ID +
                "), nickname_data AS (" +
                SELECT + "n." + NicknamesTable.USER_UUID + ',' +
                "GROUP_CONCAT(DISTINCT n." + NicknamesTable.NICKNAME + ") AS nicknames" +
                FROM + NicknamesTable.TABLE_NAME + " n" +
                INNER_JOIN + UsersTable.TABLE_NAME + " nu ON nu." + UsersTable.USER_UUID + "=n." + NicknamesTable.USER_UUID +
                INNER_JOIN + "recent_players rp ON rp." + UserInfoTable.USER_ID + "=nu." + UsersTable.ID +
                GROUP_BY + "n." + NicknamesTable.USER_UUID +
                "), geolocation_data AS (" +
                SELECT + "a." + GeoInfoTable.USER_ID + ',' +
                "a." + GeoInfoTable.GEOLOCATION +
                FROM + GeoInfoTable.TABLE_NAME + " a" +
                INNER_JOIN + "recent_players rp ON rp." + UserInfoTable.USER_ID + "=a." + GeoInfoTable.USER_ID +
                LEFT_JOIN + GeoInfoTable.TABLE_NAME + " b ON a." + GeoInfoTable.USER_ID + "=b." + GeoInfoTable.USER_ID +
                AND + "a." + GeoInfoTable.LAST_USED + "<b." + GeoInfoTable.LAST_USED +
                WHERE + "b." + GeoInfoTable.LAST_USED + IS_NULL +
                ')' +
                SELECT + "u." + UsersTable.USER_UUID + ',' +
                "u." + UsersTable.USER_NAME + ',' +
                "u." + UsersTable.REGISTERED + ',' +
                "rp." + UserInfoTable.BANNED + ',' +
                "geo." + GeoInfoTable.GEOLOCATION + ',' +
                "rp.last_seen," +
                "sm.count," +
                "sm.active_playtime," +
                activityIndex + " AS activity_index," +
                "pi." + PingTable.MIN_PING + ',' +
                "pi." + PingTable.MAX_PING + ',' +
                "pi." + PingTable.AVG_PING + ',' +
                "ni.nicknames" +
                FROM + "recent_players rp" +
                INNER_JOIN + UsersTable.TABLE_NAME + " u ON u." + UsersTable.ID + "=rp." + UserInfoTable.USER_ID +
                " CROSS JOIN activity_parameters ap" +
                LEFT_JOIN + "session_metrics sm ON sm." + SessionsTable.USER_ID + "=rp." + UserInfoTable.USER_ID +
                LEFT_JOIN + "ping_data pi ON pi." + PingTable.USER_ID + "=rp." + UserInfoTable.USER_ID +
                LEFT_JOIN + "nickname_data ni ON ni." + NicknamesTable.USER_UUID + "=u." + UsersTable.USER_UUID +
                LEFT_JOIN + "geolocation_data geo ON geo." + GeoInfoTable.USER_ID + "=rp." + UserInfoTable.USER_ID +
                ORDER_BY + "rp.last_seen DESC";

        return db.query(new QueryStatement<>(sql, 1000) {
            @Override
            public void prepare(PreparedStatement statement) throws SQLException {
                statement.setString(1, serverUUID.toString());
                statement.setInt(2, xMostRecentPlayers);
                statement.setLong(3, date - week);
                statement.setLong(4, date);
                statement.setLong(5, date - 2L * week);
                statement.setLong(6, date - week);
                statement.setLong(7, date - 3L * week);
                statement.setLong(8, date - 2L * week);
                statement.setDouble(9, Math.PI);
                statement.setLong(10, activeMsThreshold);
            }

            @Override
            public List<TablePlayer> processResults(ResultSet set) throws SQLException {
                List<TablePlayer> players = new ArrayList<>();
                while (set.next()) {
                    TablePlayer.Builder player = TablePlayer.builder()
                            .uuid(UUID.fromString(set.getString(UsersTable.USER_UUID)))
                            .name(set.getString(UsersTable.USER_NAME))
                            .geolocation(set.getString(GeoInfoTable.GEOLOCATION))
                            .registered(set.getLong(UsersTable.REGISTERED))
                            .lastSeen(set.getLong("last_seen"))
                            .sessionCount(set.getInt("count"))
                            .activePlaytime(set.getLong("active_playtime"))
                            .activityIndex(new ActivityIndex(set.getDouble("activity_index"), date))
                            .ping(new Ping(0L, serverUUID,
                                    set.getInt(PingTable.MIN_PING),
                                    set.getInt(PingTable.MAX_PING),
                                    set.getDouble(PingTable.AVG_PING)))
                            .nicknames(set.getString("nicknames"));
                    if (set.getBoolean(UserInfoTable.BANNED)) {
                        player.banned();
                    }
                    players.add(player.build());
                }
                return players;
            }
        });
    }
}