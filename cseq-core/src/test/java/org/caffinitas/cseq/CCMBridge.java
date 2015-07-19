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

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteStreamHandler;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.LogOutputStream;
import org.apache.commons.exec.PumpStreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Cluster.Builder;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.AlreadyExistsException;
import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

import static com.google.common.base.Preconditions.checkArgument;
import static org.caffinitas.cseq.TestUtils.CREATE_KEYSPACE_SIMPLE_FORMAT;
import static org.caffinitas.cseq.TestUtils.SIMPLE_KEYSPACE;

public class CCMBridge
{

    private static final Logger logger = LoggerFactory.getLogger(CCMBridge.class);

    public static final String IP_PREFIX;

    private static final String CASSANDRA_VERSION_REGEXP = "\\d\\.\\d\\.\\d+(-\\w+)?";

    /**
     * The environment variables to use when invoking CCM.  Inherits the current processes environment, but will also
     * prepend to the PATH variable the value of the 'ccm.path' property and set JAVA_HOME variable to the
     * 'ccm.java.home' variable.
     *
     * At times it is necessary to use a separate java install for CCM then what is being used for running tests.
     * For example, if you want to run tests with JDK 6 but against Cassandra 2.0, which requires JDK 7.
     */
    private static final Map<String,String> ENVIRONMENT_MAP;

    static {
        // Inherit the current environment.
        Map<String,String> envMap = Maps.newHashMap(new ProcessBuilder().environment());
        // If ccm.path is set, override the PATH variable with it.
        String ccmPath = System.getProperty("ccm.path");
        if(ccmPath != null) {
            String existingPath = envMap.get("PATH");
            if(existingPath == null) {
                existingPath = "";
            }
            envMap.put("PATH", ccmPath + ':' + existingPath);
        }
        // If ccm.java.home is set, override the JAVA_HOME variable with it.
        String ccmJavaHome = System.getProperty("ccm.java.home");
        if(ccmJavaHome != null) {
            envMap.put("JAVA_HOME", ccmJavaHome);
        }
        ENVIRONMENT_MAP = ImmutableMap.copyOf(envMap);
    }

    static final File CASSANDRA_DIR;
    static final String CASSANDRA_VERSION;

    static {
        String version = System.getProperty("cassandra.version");
        if (version.matches(CASSANDRA_VERSION_REGEXP)) {
            CASSANDRA_DIR = null;
            CASSANDRA_VERSION = "-v " + version;
        } else {
            CASSANDRA_DIR = new File(version);
            CASSANDRA_VERSION = "";
        }

        String ip_prefix = System.getProperty("ipprefix");
        if (ip_prefix == null || ip_prefix.isEmpty()) {
            ip_prefix = "127.0.1.";
        }
        IP_PREFIX = ip_prefix;
    }

    private final File ccmDir;

    private CCMBridge() {
        this.ccmDir = Files.createTempDir();
    }

    /** Note that this method does not start CCM */
    public static CCMBridge create(String name, String... options) {
        // This leads to a confusing CCM error message so check explicitly:
        checkArgument(!"current".equals(name.toLowerCase()),
            "cluster can't be called \"current\"");
        CCMBridge bridge = new CCMBridge();
        bridge.execute("ccm create %s -b -i %s %s " + Joiner.on(" ").join(options), name, IP_PREFIX, CASSANDRA_VERSION);
        return bridge;
    }

    public static CCMBridge create(String name, int nbNodes, String... options) {
        checkArgument(!"current".equals(name.toLowerCase()),
            "cluster can't be called \"current\"");
        CCMBridge bridge = new CCMBridge();
        bridge.execute("ccm create %s -n %d -s -i %s -b %s " + Joiner.on(" ").join(options), name, nbNodes, IP_PREFIX, CASSANDRA_VERSION);
        return bridge;
    }

    public static CCMBridge create(String name, int nbNodesDC1, int nbNodesDC2) {
        checkArgument(!"current".equals(name.toLowerCase()),
            "cluster can't be called \"current\"");
        CCMBridge bridge = new CCMBridge();
        bridge.execute("ccm create %s -n %d:%d -s -i %s -b %s", name, nbNodesDC1, nbNodesDC2, IP_PREFIX, CASSANDRA_VERSION);
        return bridge;
    }

    public void stop() {
        execute("ccm stop");
    }

    public void start(int n) {
        logger.info("Starting: " + IP_PREFIX + n);
        execute("ccm node%d start --wait-other-notice --wait-for-binary-proto", n);
    }

    public void start(int n, String option) {
        logger.info("Starting: " + IP_PREFIX + n + " with " + option);
        execute("ccm node%d start --wait-other-notice --wait-for-binary-proto --jvm_arg=%s", n, option);
    }

    public void stop(String clusterName) {
        logger.info("Stopping Cluster : "+clusterName);
        execute("ccm stop "+clusterName);
    }

    public void remove() {
        stop();
        execute("ccm remove");
    }

    public void remove(String clusterName) {
        stop(clusterName);
        execute("ccm remove " + clusterName);
    }

    public void bootstrapNodeWithPorts(int n, int thriftPort, int storagePort, int binaryPort, int jmxPort, int remoteDebugPort, String option) {
        String thriftItf = IP_PREFIX + n + ':' + thriftPort;
        String storageItf = IP_PREFIX + n + ':' + storagePort;
        String binaryItf = IP_PREFIX + n + ':' + binaryPort;
        String remoteLogItf = IP_PREFIX + n + ':' + remoteDebugPort;
        execute("ccm add node%d -i %s%d -b -t %s -l %s --binary-itf %s -j %d -r %s -s",
            n, IP_PREFIX, n, thriftItf, storageItf, binaryItf, jmxPort, remoteLogItf);
        if(option == null) start(n);
        else start(n, option);
    }

    private void execute(String command, Object... args) {
        try {
            String fullCommand = String.format(command, args) + " --config-dir=" + ccmDir;
            logger.debug("Executing: " + fullCommand);
            CommandLine cli = CommandLine.parse(fullCommand);
            Executor executor = new DefaultExecutor();

            LogOutputStream outStream = new LogOutputStream() {
                @Override
                protected void processLine(String line, int logLevel) {
                    logger.debug("ccmout> " + line);
                }
            };
            LogOutputStream errStream = new LogOutputStream() {
                @Override
                protected void processLine(String line, int logLevel) {
                    logger.error("ccmerr> " + line);
                }
            };

            ExecuteStreamHandler streamHandler = new PumpStreamHandler(outStream, errStream);
            executor.setStreamHandler(streamHandler);

            int retValue = executor.execute(cli, ENVIRONMENT_MAP);
            if (retValue != 0) {
                logger.error("Non-zero exit code ({}) returned from executing ccm command: {}", retValue, fullCommand);
                throw new RuntimeException();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean pingPort(InetAddress address, int port) {
        logger.debug("Trying {}:{}...", address, port);
        boolean connectionSuccessful = false;
        Socket socket = null;
        try {
            socket = new Socket(address, port);
            connectionSuccessful = true;
            logger.debug("Successfully connected");
        } catch (IOException e) {
            logger.debug("Connection failed");
        } finally {
            if (socket != null)
                try {
                    socket.close();
                } catch (IOException e) {
                    logger.warn("Error closing socket to " + address);
                }
        }
        return connectionSuccessful;
    }

    public static String ipOfNode(int nodeNumber) {
        return IP_PREFIX + Integer.toString(nodeNumber);
    }

    public static class TerminationHook extends Thread
    {
        public void run() {
            logger.debug("shut down hook task..");

            if (PerClassSingleNodeCluster.cluster != null) {
                PerClassSingleNodeCluster.cluster.close();
            }
            if (PerClassSingleNodeCluster.ccmBridge == null) {
                logger.error("No cluster to discard");
            } else if (PerClassSingleNodeCluster.erroredOut) {
                PerClassSingleNodeCluster.ccmBridge.remove("test-class");
                logger.info("Error during tests, kept C* logs in " + PerClassSingleNodeCluster.ccmBridge.ccmDir);
            } else {
                PerClassSingleNodeCluster.ccmBridge.remove("test-class");
                PerClassSingleNodeCluster.ccmBridge.ccmDir.delete();
            }

        }
    }

    // One cluster for the whole test class
    public static abstract class PerClassSingleNodeCluster {

        /**
         * The JVM args to use when starting nodes.
         * Unfortunately, this must be set for all tests because
         * CCM cluster instances are reused between tests,
         * so a specific test cannot ask for specific JVM options.
         */
        private static final String JVM_ARGS =
            // custom query handler to test protocol v4 custom payloads
            "-Dcassandra.custom_query_handler_class=org.apache.cassandra.cql3.CustomPayloadMirroringQueryHandler";

        protected static CCMBridge ccmBridge;
        private static boolean erroredOut;
        private static boolean clusterInitialized=false;
        private static AtomicLong ksNumber;
        protected String keyspace;

        protected static InetSocketAddress hostAddress;
        protected static int[] ports;

        protected static Cluster cluster;
        protected static Session session;

        protected abstract Collection<String> getTableDefinitions();

        // Give individual tests a chance to customize the cluster configuration
        protected Cluster.Builder configure(Cluster.Builder builder) {
            return builder;
        }

        public void errorOut() {
            erroredOut = true;
        }

        @BeforeClass(groups = { "short", "long" })
        public void beforeClass() {
            maybeInitCluster();
            initKeyspace();
        }

        @AfterClass(groups = { "short", "long" })
        public void afterClass() {
            clearSimpleKeyspace();
        }

        private void maybeInitCluster(){
            if (!clusterInitialized){
                try {
                    //launch ccm cluster
                    ccmBridge = CCMBridge.create("test-class");
                    // Only enable user defined functions if protocol version is >= 4.
//                    if(TestUtils.getDesiredProtocolVersion().compareTo(ProtocolVersion.V4) >= 0)
//                        ccmBridge.updateConfig("enable_user_defined_functions", "true");

                    ports = new int[5];
                    for (int i = 0; i < 5; i++) {
                        ports[i] = TestUtils.findAvailablePort(11000 + i);
                    }

                    ccmBridge.bootstrapNodeWithPorts(1, ports[0], ports[1], ports[2], ports[3], ports[4], JVM_ARGS);
                    ksNumber = new AtomicLong(0);
                    erroredOut = false;
                    hostAddress = new InetSocketAddress(InetAddress.getByName(IP_PREFIX + 1), ports[2]);

                    Runtime r = Runtime.getRuntime();
                    r.addShutdownHook(new TerminationHook());
                    clusterInitialized = true;

                } catch (UnknownHostException e) {
                    throw new RuntimeException(e);
                }
            }

        }


        private void initKeyspace() {
            try {
                Builder builder = Cluster.builder();

                builder = configure(builder);

                cluster = builder.addContactPointsWithPorts(Collections.singletonList(hostAddress)).build();
                session = cluster.connect();
                keyspace = SIMPLE_KEYSPACE + '_' + ksNumber.incrementAndGet();
                session.execute(String.format(CREATE_KEYSPACE_SIMPLE_FORMAT, keyspace, 1));

                session.execute("USE " + keyspace);
                for (String tableDef : getTableDefinitions()) {
                    try {
                        session.execute(tableDef);
                    } catch (AlreadyExistsException e) {
                        // It's ok, ignore
                    }
                }
            } catch (AlreadyExistsException e) {
                // It's ok, ignore (not supposed to go there)
            } catch (NoHostAvailableException e) {
                erroredOut = true;
                for (Map.Entry<InetSocketAddress, Throwable> entry : e.getErrors().entrySet())
                    logger.info("Error connecting to " + entry.getKey() + ": " + entry.getValue());
                throw new RuntimeException(e);
            } catch (DriverException e) {
                erroredOut = true;
                throw e;
            }
        }

        private void clearSimpleKeyspace() {
            session.execute("DROP KEYSPACE " + keyspace);
            if (cluster != null) {
                cluster.close();
            }
        }

    }

    public static class CCMCluster {

        public final Cluster cluster;
        public final Session session;

        public final CCMBridge cassandraCluster;

        private boolean erroredOut;

        public static CCMCluster create(int nbNodes, Cluster.Builder builder) {
            if (nbNodes == 0)
                throw new IllegalArgumentException();

            return new CCMCluster(CCMBridge.create("test", nbNodes), builder, nbNodes);
        }

        public static CCMCluster create(int nbNodesDC1, int nbNodesDC2, Cluster.Builder builder) {
            if (nbNodesDC1 == 0)
                throw new IllegalArgumentException();

            return new CCMCluster(CCMBridge.create("test", nbNodesDC1, nbNodesDC2), builder, nbNodesDC1 + nbNodesDC2);
        }

        public static CCMCluster create(CCMBridge cassandraCluster, Cluster.Builder builder, int totalNodes) {
            return new CCMCluster(cassandraCluster, builder, totalNodes);
        }

        private CCMCluster(CCMBridge cassandraCluster, Cluster.Builder builder, int totalNodes) {
            this.cassandraCluster = cassandraCluster;
            try {
                String[] contactPoints = new String[totalNodes];
                for (int i = 0; i < totalNodes; i++)
                    contactPoints[i] = IP_PREFIX + (i + 1);

                try {
                    Thread.sleep(1000);
                } catch (Exception e) {
                }
                this.cluster = builder.addContactPoints(contactPoints).build();
                this.session = cluster.connect();
            } catch (NoHostAvailableException e) {
                for (Map.Entry<InetSocketAddress, Throwable> entry : e.getErrors().entrySet())
                    logger.info("Error connecting to " + entry.getKey() + ": " + entry.getValue());
                discard();
                throw new RuntimeException(e);
            }
        }

        public void errorOut() {
            erroredOut = true;
        }

        public void discard() {
            if (cluster != null)
                cluster.close();

            if (cassandraCluster == null) {
                logger.error("No cluster to discard");
            } else if (erroredOut) {
                cassandraCluster.stop();
                logger.info("Error during tests, kept C* logs in " + cassandraCluster.ccmDir);
            } else {
                cassandraCluster.remove();
                cassandraCluster.ccmDir.delete();
            }
        }
    }
}
