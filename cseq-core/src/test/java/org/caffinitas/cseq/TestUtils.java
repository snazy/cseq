/*
 *      Copyright (C) 2012-2015 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.caffinitas.cseq;

import java.io.IOException;
import java.net.ServerSocket;

import com.datastax.driver.core.ProtocolVersion;
import org.testng.SkipException;

/**
 * A number of static fields/methods handy for tests.
 */
public abstract class TestUtils
{

    public static final String CREATE_KEYSPACE_SIMPLE_FORMAT = "CREATE KEYSPACE %s WITH replication = { 'class' : 'SimpleStrategy', 'replication_factor' : %d }";

    public static final String SIMPLE_KEYSPACE = "ks";

    public static void versionCheck(double majorCheck, int minorCheck, String skipString) {
        String version = System.getProperty("cassandra.version");
        String[] versionArray = version.split("\\.|-");
        double major = Double.parseDouble(versionArray[0] + '.' + versionArray[1]);
        int minor = Integer.parseInt(versionArray[2]);

        if (major < majorCheck || (major == majorCheck && minor < minorCheck)) {
            throw new SkipException("Version >= " + majorCheck + '.' + minorCheck + " required.  Description: " + skipString);
        }
    }

    /**
     * @param startingWith The first port to try, if unused will keep trying the next port until one is found up to
     *                     100 subsequent ports.
     * @return A local port that is currently unused.
     */
    public static int findAvailablePort(int startingWith) {
        IOException last = null;
        for (int port = startingWith; port < startingWith + 100; port++) {
            try {
                ServerSocket s = new ServerSocket(port);
                s.close();
                return port;
            } catch (IOException e) {
                last = e;
            }
        }
        // If for whatever reason a port could not be acquired throw the last encountered exception.
        throw new RuntimeException("Could not acquire an available port", last);
    }

    /**
     * @return The desired target protocol version based on the 'cassandra.version' System property.
     */
    public static ProtocolVersion getDesiredProtocolVersion() {
        String version = System.getProperty("cassandra.version");
        String[] versionArray = version.split("\\.|-");
        double major = Double.parseDouble(versionArray[0] + '.' + versionArray[1]);
        if(major < 2.0) {
            return ProtocolVersion.V1;
        } else if (major < 2.1) {
            return ProtocolVersion.V2;
        } else if (major < 2.2) {
            return ProtocolVersion.V3;
        } else {
            return ProtocolVersion.V4;
        }
    }
}
