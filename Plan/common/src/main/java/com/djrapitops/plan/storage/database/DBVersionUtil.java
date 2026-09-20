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
package com.djrapitops.plan.storage.database;

import com.djrapitops.plan.exceptions.database.DBInitException;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DBVersionUtil {

    static final int MINIMUM_MYSQL_MAJOR_VERSION = 8;
    static final int MINIMUM_MYSQL_MINOR_VERSION = 4;
    static final int MINIMUM_MARIADB_MAJOR_VERSION = 11;
    static final int MINIMUM_MARIADB_MINOR_VERSION = 4;

    private static final Pattern MYSQL_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.\\d+)?(?:-|$)");

    private static final Pattern MARIADB_VERSION =
            Pattern.compile("(\\d+)\\.(\\d+)(?:\\.\\d+)?-MariaDB(?:-|$)", Pattern.CASE_INSENSITIVE);

    private DBVersionUtil() {
    }

    static void ensureSupported(String serverVersion) {
        boolean mariaDB = serverVersion.toLowerCase(Locale.ROOT).contains("mariadb");
        Matcher matcher = (mariaDB ? MARIADB_VERSION : MYSQL_VERSION).matcher(serverVersion);
        if (!matcher.find()) {
            throw new DBInitException("Could not determine MySQL/MariaDB server version from '" + serverVersion + "'.");
        }

        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int requiredMajor = mariaDB ? MINIMUM_MARIADB_MAJOR_VERSION : MINIMUM_MYSQL_MAJOR_VERSION;
        int requiredMinor = mariaDB ? MINIMUM_MARIADB_MINOR_VERSION : MINIMUM_MYSQL_MINOR_VERSION;
        if (major < requiredMajor || major == requiredMajor && minor < requiredMinor) {
            String database = mariaDB ? "MariaDB" : "MySQL";
            throw new DBInitException(
                    "Unsupported " + database + " server version '" + serverVersion + "'. " +
                            "Plan requires " + database + ' ' + requiredMajor + '.' + requiredMinor +
                            " or newer. Upgrade the database server before starting Plan."
            );
        }
    }
}
