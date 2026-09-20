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
import com.djrapitops.plan.storage.database.queries.analysis.ActivityIndexQueries;
import com.djrapitops.plan.storage.database.sql.tables.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
        String serverId = "(SELECT server_id FROM server_context)";
        String sql = "WITH " +
                cte("server_context", selectServerContext()) + ',' +
                cte("session_last_seen", selectSessionLastSeen(serverId)) + ',' +
                cte("recent_players", selectRecentPlayers(serverId)) + ',' +
                cte("session_metrics", selectSessionMetrics(serverId)) + ',' +
                cte("activity_parameters", ActivityIndexQueries.activityIndexParametersSQL()) + ',' +
                cte("ping_data", selectPingData(serverId)) + ',' +
                cte("nickname_data", selectNicknameData()) + ',' +
                cte("geolocation_data", selectGeolocationData()) +
                selectPlayers();

        return db.query(new QueryStatement<>(sql, 1000) {
            @Override
            public void prepare(PreparedStatement statement) throws SQLException {
                statement.setString(1, serverUUID.toString());
                statement.setInt(2, xMostRecentPlayers);
                ActivityIndexQueries.setWeeklyActivePlaytimeParameters(statement, 3, date);
                ActivityIndexQueries.setActivityIndexParameters(statement, 9, activeMsThreshold);
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

    private static String selectServerContext() {
        return SELECT + ServerTable.ID + " AS server_id" +
                FROM + ServerTable.TABLE_NAME +
                WHERE + ServerTable.SERVER_UUID + "=?" +
                LIMIT + '1';
    }

    private static String selectSessionLastSeen(String serverId) {
        return SELECT + "s." + SessionsTable.USER_ID + ',' +
                max("s." + SessionsTable.SESSION_END) + " AS last_seen" +
                FROM + SessionsTable.TABLE_NAME + " s" +
                WHERE + "s." + SessionsTable.SERVER_ID + '=' + serverId +
                GROUP_BY + "s." + SessionsTable.USER_ID;
    }

    private static String selectRecentPlayers(String serverId) {
        return SELECT + "ui." + UserInfoTable.USER_ID + ',' +
                "ui." + UserInfoTable.BANNED + ',' +
                "sls.last_seen" +
                FROM + UserInfoTable.TABLE_NAME + " ui" +
                LEFT_JOIN + "session_last_seen sls ON sls." + SessionsTable.USER_ID + "=ui." + UserInfoTable.USER_ID +
                WHERE + "ui." + UserInfoTable.SERVER_ID + '=' + serverId +
                ORDER_BY + "sls.last_seen DESC" +
                LIMIT + '?';
    }

    private static String selectSessionMetrics(String serverId) {
        return SELECT + "s." + SessionsTable.USER_ID + ',' +
                "COUNT(1) AS count," +
                sum(ActivityIndexQueries.activePlaytimeSQL("s")) + " AS active_playtime," +
                ActivityIndexQueries.weeklyActivePlaytimeSQL("s") +
                FROM + SessionsTable.TABLE_NAME + " s" +
                INNER_JOIN + "recent_players rp ON rp." + UserInfoTable.USER_ID + "=s." + SessionsTable.USER_ID +
                WHERE + "s." + SessionsTable.SERVER_ID + '=' + serverId +
                GROUP_BY + "s." + SessionsTable.USER_ID;
    }

    private static String selectPingData(String serverId) {
        return SELECT + "p." + PingTable.USER_ID + ',' +
                avg("p." + PingTable.AVG_PING) + " AS " + PingTable.AVG_PING + ',' +
                max("p." + PingTable.MAX_PING) + " AS " + PingTable.MAX_PING + ',' +
                min("p." + PingTable.MIN_PING) + " AS " + PingTable.MIN_PING +
                FROM + PingTable.TABLE_NAME + " p" +
                INNER_JOIN + "recent_players rp ON rp." + UserInfoTable.USER_ID + "=p." + PingTable.USER_ID +
                WHERE + "p." + PingTable.SERVER_ID + '=' + serverId +
                GROUP_BY + "p." + PingTable.USER_ID;
    }

    private static String selectNicknameData() {
        return SELECT + "n." + NicknamesTable.USER_UUID + ',' +
                "GROUP_CONCAT(DISTINCT n." + NicknamesTable.NICKNAME + ") AS nicknames" +
                FROM + NicknamesTable.TABLE_NAME + " n" +
                INNER_JOIN + UsersTable.TABLE_NAME + " nu ON nu." + UsersTable.USER_UUID + "=n." + NicknamesTable.USER_UUID +
                INNER_JOIN + "recent_players rp ON rp." + UserInfoTable.USER_ID + "=nu." + UsersTable.ID +
                GROUP_BY + "n." + NicknamesTable.USER_UUID;
    }

    private static String selectGeolocationData() {
        return SELECT + "a." + GeoInfoTable.USER_ID + ',' +
                "a." + GeoInfoTable.GEOLOCATION +
                FROM + GeoInfoTable.TABLE_NAME + " a" +
                INNER_JOIN + "recent_players rp ON rp." + UserInfoTable.USER_ID + "=a." + GeoInfoTable.USER_ID +
                LEFT_JOIN + GeoInfoTable.TABLE_NAME + " b ON a." + GeoInfoTable.USER_ID + "=b." + GeoInfoTable.USER_ID +
                AND + "(a." + GeoInfoTable.LAST_USED + "<b." + GeoInfoTable.LAST_USED +
                OR + "(a." + GeoInfoTable.LAST_USED + "=b." + GeoInfoTable.LAST_USED +
                AND + "a." + GeoInfoTable.ID + "<b." + GeoInfoTable.ID + "))" +
                WHERE + "b." + GeoInfoTable.ID + IS_NULL;
    }

    private static String selectPlayers() {
        return SELECT + "u." + UsersTable.USER_UUID + ',' +
                "u." + UsersTable.USER_NAME + ',' +
                "u." + UsersTable.REGISTERED + ',' +
                "rp." + UserInfoTable.BANNED + ',' +
                "geo." + GeoInfoTable.GEOLOCATION + ',' +
                "rp.last_seen," +
                "sm.count," +
                "sm.active_playtime," +
                ActivityIndexQueries.activityIndexFromWeeklyPlaytimeSQL("sm", "ap") + " AS activity_index," +
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
    }

    private static String cte(String name, String query) {
        return name + " AS (" + query + ')';
    }
}