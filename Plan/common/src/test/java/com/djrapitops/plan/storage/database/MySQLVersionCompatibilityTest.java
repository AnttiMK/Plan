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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySQLVersionCompatibilityTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "8.4.0",
            "8.4.3-commercial",
            "9.0.1",
            "11.4.0-MariaDB",
            "11.4.5-MariaDB-log",
            "5.5.5-11.4.5-MariaDB",
            "12.0.1-MariaDB"
    })
    void supportedVersionsAreAccepted(String serverVersion) {
        assertDoesNotThrow(() -> DBVersionUtil.ensureSupported(serverVersion));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8.3.0",
            "5.7.44-log",
            "11.3.2-MariaDB",
            "5.5.5-10.11.11-MariaDB"
    })
    void unsupportedVersionsAreRejected(String serverVersion) {
        DBInitException exception = assertThrows(
                DBInitException.class,
                () -> DBVersionUtil.ensureSupported(serverVersion)
        );
        assertTrue(exception.getMessage().contains(serverVersion));
        assertTrue(exception.getMessage().contains("Upgrade"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "MariaDB", ""})
    void malformedVersionsAreRejected(String serverVersion) {
        assertThrows(DBInitException.class, () -> DBVersionUtil.ensureSupported(serverVersion));
    }
}
