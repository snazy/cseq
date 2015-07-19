/*
 *      Copyright (C) 2014 Robert Stupp, Koeln, Germany, robert-stupp.de
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
package org.caffinitas.cseq.sequences;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import org.caffinitas.cseq.nodeid.NodeId;

/**
 * TODO add javadoc explaining the purpose of this class/interface/annotation
 */
public class SequenceSession
{
    private static final Logger logger = LoggerFactory.getLogger(SequenceSession.class);

    public static final String SEQUENCES_INVENTORY = "cseq_inventory";
    public static final String SEQUENCES_GLOBAL = "cseq_global";
    public static final String SEQUENCES_LOCAL = "cseq_local";

    final Session session;
    private final String keyspace;
    private final PreparedStatement pstmtSelectSequenceInventory;
    private final PreparedStatement pstmtInsertSequenceInventory;
    //    private final PreparedStatement pstmtDeleteSequenceInventory;
    final PreparedStatement pstmtSelectSequenceLocal;
    final PreparedStatement pstmtUpdateSequenceLocal;
    final PreparedStatement pstmtRemoveSequenceLocal;
    final PreparedStatement pstmtCreateSequencePeer;
    final PreparedStatement pstmtSelectSequencePeer;
    final PreparedStatement pstmtUpdateSequencePeer;
    final PreparedStatement pstmtRemoveSequencePeer;
    final NodeId nodeId;

    private final Map<String, Sequence> openSequences = new HashMap<>();

    /**
     * Create the container for all sequence used by an application via a Java Driver
     * {@link Session}.
     *
     * @param session  Java Driver {@link Session} to use
     * @param keyspace Keyspace in which the the sequences shall live
     * @param nodeId   ID of the node
     */
    public SequenceSession(Session session, String keyspace, NodeId nodeId)
    {
        if (session == null)
            throw new NullPointerException("session parameter is null");
        if (keyspace == null)
            keyspace = session.getLoggedKeyspace();
        if (keyspace == null)
            throw new NullPointerException("keyspace parameter is null and no logged keyspace in session");
        if (nodeId == null)
            throw new NullPointerException("nodeId parameter is null");
        this.session = session;
        this.keyspace = keyspace;
        this.nodeId = nodeId;

        KeyspaceMetadata ksMeta = session.getCluster().getMetadata().getKeyspace(keyspace);
        if (ksMeta == null)
            throw new IllegalStateException("Keyspace " + keyspace + " does not exist");
        if (ksMeta.getTable(SEQUENCES_INVENTORY) == null)
            session.execute(createSequencesInventory());
        if (ksMeta.getTable(SEQUENCES_GLOBAL) == null)
            session.execute(createSequencesGlobal());
        if (ksMeta.getTable(SEQUENCES_LOCAL) == null)
            session.execute(createSequencesLocal());

        this.pstmtSelectSequenceInventory = session.prepare("SELECT minval, maxval, start_with, incr_by, cache, cache_local, cl_serial, cl" +
                                                            " FROM " + keyspace + '.' + SEQUENCES_INVENTORY +
                                                            " WHERE sequence_name =?");
        this.pstmtInsertSequenceInventory = session.prepare("INSERT INTO " + keyspace + '.' + SEQUENCES_INVENTORY +
                                                            " (sequence_name, minval, maxval, start_with, incr_by, cache, cache_local, cl_serial, cl)" +
                                                            " VALUES" +
                                                            " (?, ?, ?, ?, ?, ?, ?, ?, ?)" +
                                                            " IF NOT EXISTS");
//        this.pstmtDeleteSequenceInventory = session.prepare("DELETE " +
//                                                            " FROM " + keyspace + '.' + SEQUENCES_INVENTORY +
//                                                            " WHERE sequence_name =?");

        this.pstmtSelectSequenceLocal = session.prepare("SELECT nextval, reserved" +
                                                        " FROM " + keyspace + '.' + SEQUENCES_LOCAL +
                                                        " WHERE sequence_name =?" +
                                                        " AND peer =?");
        this.pstmtUpdateSequenceLocal = session.prepare("UPDATE " + keyspace + '.' + SEQUENCES_LOCAL +
                                                        " SET nextval =?, reserved =?" +
                                                        " WHERE sequence_name =?" +
                                                        " AND peer =?");
        this.pstmtRemoveSequenceLocal = session.prepare("DELETE FROM " + keyspace + '.' + SEQUENCES_LOCAL +
                                                        " WHERE sequence_name =?" +
                                                        " AND peer =?");

        this.pstmtCreateSequencePeer = session.prepare("INSERT INTO " + keyspace + '.' + SEQUENCES_GLOBAL +
                                                       " (sequence_name, nextval, exhausted) VALUES (?, ?, false)" +
                                                       " IF NOT EXISTS");
        this.pstmtSelectSequencePeer = session.prepare("SELECT nextval, exhausted" +
                                                       " FROM " + keyspace + '.' + SEQUENCES_GLOBAL +
                                                       " WHERE sequence_name =?");
        this.pstmtUpdateSequencePeer = session.prepare("UPDATE " + keyspace + '.' + SEQUENCES_GLOBAL +
                                                       " SET nextval =?, exhausted =? " +
                                                       " WHERE sequence_name =? " +
                                                       " IF nextval =?");
        this.pstmtRemoveSequencePeer = session.prepare("DELETE FROM " + keyspace + '.' + SEQUENCES_GLOBAL +
                                                       " WHERE sequence_name =?");
    }

    private String createSequencesLocal()
    {
        return "CREATE TABLE " + this.keyspace + '.' + SEQUENCES_LOCAL + " ("
               + "sequence_name text,"
               + "peer blob,"
               + "nextval bigint,"
               + "reserved bigint,"
               + "PRIMARY KEY ((sequence_name, peer)))";
    }

    private String createSequencesGlobal()
    {
        return "CREATE TABLE " + this.keyspace + '.' + SEQUENCES_GLOBAL + " ("
               + "sequence_name text,"
               + "nextval bigint,"
               + "exhausted boolean,"
               + "PRIMARY KEY ((sequence_name)))";
    }

    private String createSequencesInventory()
    {
        return "CREATE TABLE " + this.keyspace + '.' + SEQUENCES_INVENTORY + " ("
               + "sequence_name text,"
               + "minval bigint,"
               + "maxval bigint,"
               + "start_with bigint,"
               + "incr_by bigint,"
               + "cache bigint,"
               + "cache_local bigint,"
               + "cl_serial ascii,"
               + "cl ascii,"
               + "PRIMARY KEY ((sequence_name)))";
    }

    /**
     * Create a builder for a sequence that does not exist yet.
     */
    public SequenceBuilder builder()
    {
        return new SequenceBuilder();
    }

    Sequence create(SequenceBuilder sequenceBuilder)
    {
        if (!session.execute(pstmtInsertSequenceInventory.bind(
                                                              sequenceBuilder.name,
                                                              sequenceBuilder.minValue,
                                                              sequenceBuilder.maxValue,
                                                              sequenceBuilder.startWith,
                                                              sequenceBuilder.incrementBy,
                                                              sequenceBuilder.cache,
                                                              sequenceBuilder.cacheLocal,
                                                              sequenceBuilder.serialConsistencyLevel.name(),
                                                              sequenceBuilder.consistencyLevel.name()
        )).wasApplied())
            throw new IllegalStateException("Sequence " + sequenceBuilder.name + " already exists");

        synchronized (openSequences)
        {
            if (openSequences.containsKey(sequenceBuilder.name))
                throw new IllegalStateException("Sequence " + sequenceBuilder.name + " already exists");

            Sequence seq = new Sequence(this,
                                        sequenceBuilder.name,
                                        sequenceBuilder.startWith,
                                        sequenceBuilder.incrementBy,
                                        sequenceBuilder.minValue,
                                        sequenceBuilder.maxValue,
                                        sequenceBuilder.cache,
                                        sequenceBuilder.cacheLocal,
                                        sequenceBuilder.consistencyLevel,
                                        sequenceBuilder.serialConsistencyLevel);
            createSequenceGlobal(seq);
            return seq;
        }
    }

//    void removeSequence()
//    {
//        removeLocalCached();
//
//        sequenceSession.session.execute(sequenceSession.pstmtRemoveSequencePeer.bind(name)
//                                                                               .setConsistencyLevel(consistencyLevel));
//    }

    /**
     * Creates the global part of the sequence in {@code system_distributed} keyspace.
     * Does not add the sequence to the internal structures or per-node sequence status table.
     */
    void createSequenceGlobal(Sequence seq)
    {
        if (createPeer(seq))
        {
            if (logger.isDebugEnabled())
                logger.debug("Sequence {} initialized in distributed table", seq.name);
        }
        else
            throw new IllegalStateException("Sequence " + seq.name + " already exists");
    }

    boolean createPeer(Sequence seq)
    {
        boolean applied = session.execute(pstmtCreateSequencePeer.bind(seq.name,
                                                                       seq.startWith)
                                                                 .setConsistencyLevel(seq.consistencyLevel)
                                                                 .setSerialConsistencyLevel(seq.serialConsistencyLevel))
                                 .wasApplied();
        return applied;
    }

    void sequenceClosed(Sequence sequence)
    {
        synchronized (openSequences)
        {
            openSequences.remove(sequence.name);
        }
    }

    /**
     * Open an already existing sequence.
     *
     * @param name name of the sequence to open
     * @return opened sequence
     * @throws IllegalStateException if the sequence does not exist
     */
    public Sequence open(String name)
    {
        Row row = session.execute(pstmtSelectSequenceInventory.bind(name)).one();
        if (row == null)
            throw new IllegalStateException("Sequence " + name + " does not exist");

        synchronized (openSequences)
        {
            Sequence seq = openSequences.get(name);
            if (seq != null)
                return seq;
            seq = new Sequence(this,
                               name,
                               row.getLong("start_with"),
                               row.getLong("incr_by"),
                               row.getLong("minval"),
                               row.getLong("maxval"),
                               row.getLong("cache"),
                               row.getLong("cache_local"),
                               ConsistencyLevel.valueOf(row.getString("cl")),
                               ConsistencyLevel.valueOf(row.getString("cl_serial")));
            openSequences.put(name, seq);
            return seq;
        }
    }

    public class SequenceBuilder
    {
        private String name;
        private Long startWith;
        private Long incrementBy;
        private Long minValue;
        private Long maxValue;
        private Long cache;
        private Long cacheLocal;
        private ConsistencyLevel consistencyLevel;
        private ConsistencyLevel serialConsistencyLevel;

        public SequenceBuilder name(String name)
        {
            this.name = name;
            return this;
        }

        public SequenceBuilder startWith(long startWith)
        {
            this.startWith = startWith;
            return this;
        }

        public SequenceBuilder incrementBy(long incrementBy)
        {
            this.incrementBy = incrementBy;
            return this;
        }

        public SequenceBuilder minValue(long minValue)
        {
            this.minValue = minValue;
            return this;
        }

        public SequenceBuilder maxValue(long maxValue)
        {
            this.maxValue = maxValue;
            return this;
        }

        public SequenceBuilder cache(long cache)
        {
            this.cache = cache;
            return this;
        }

        public SequenceBuilder cacheLocal(long cacheLocal)
        {
            this.cacheLocal = cacheLocal;
            return this;
        }

        public SequenceBuilder consistencyLevel(ConsistencyLevel consistencyLevel)
        {
            this.consistencyLevel = consistencyLevel;
            return this;
        }

        public SequenceBuilder serialConsistencyLevel(ConsistencyLevel serialConsistencyLevel)
        {
            this.serialConsistencyLevel = serialConsistencyLevel;
            return this;
        }

        /**
         * Creates the new sequence.
         * The default parameters, if not set explicitly, are:
         * <table>
         *     <tr>
         *         <th>Parameter</th>
         *         <th>Default value</th>
         *     </tr>
         *     <tr>
         *         <td>incrementBy</td>
         *         <td>{@code 1}</td>
         *     </tr>
         *     <tr>
         *         <td>minValue</td>
         *         <td>{@code 1} if {@code incrementBy&gt;0}, {@code Long.MIN_VALUE} otherwise</td>
         *     </tr>
         *     <tr>
         *         <td>maxValue</td>
         *         <td>{@code Long.MAX_VALUE} if {@code incrementBy&gt;0}, {@code -1} otherwise</td>
         *     </tr>
         *     <tr>
         *         <td>startWith</td>
         *         <td>minValue if {@code incrementBy&gt;0}, maxValue otherwise</td>
         *     </tr>
         *     <tr>
         *         <td>cache</td>
         *         <td>1</td>
         *     </tr>
         *     <tr>
         *         <td>cacheLocal</td>
         *         <td>cache</td>
         *     </tr>
         *     <tr>
         *         <td>serialConsistencyLevel</td>
         *         <td>{@code SERIAL}</td>
         *     </tr>
         *     <tr>
         *         <td>consistencyLevel</td>
         *         <td>{@code QUORUM}</td>
         *     </tr>
         * </table>
         */
        public Sequence create()
        {
            if (this.name == null || this.name.trim().isEmpty())
                throw new IllegalArgumentException("Sequene name must not be empty");
            this.name = this.name.trim();
            if (this.incrementBy == null)
                this.incrementBy = 1L;
            if (this.minValue == null)
                this.minValue = (this.incrementBy > 0 ? 1L : Long.MIN_VALUE);
            if (this.maxValue == null)
                this.maxValue = (this.incrementBy > 0 ? Long.MAX_VALUE : -1L);
            if (this.startWith == null)
                this.startWith = (this.incrementBy > 0 ? this.minValue : this.maxValue);
            if (this.cache == null)
                this.cache = 1L;
            this.cacheLocal = Math.abs(cacheLocal != null ? this.cacheLocal : this.cache);
            if (this.serialConsistencyLevel == null)
                this.serialConsistencyLevel = ConsistencyLevel.SERIAL;
            if (this.consistencyLevel == null)
                this.consistencyLevel = ConsistencyLevel.QUORUM;

            if (this.consistencyLevel == ConsistencyLevel.SERIAL || this.consistencyLevel == ConsistencyLevel.LOCAL_SERIAL)
                throw new IllegalArgumentException("Illegal consistency level " + this.consistencyLevel + " for attribute consistencyLevel");
            if (this.serialConsistencyLevel != ConsistencyLevel.SERIAL && this.serialConsistencyLevel != ConsistencyLevel.LOCAL_SERIAL)
                throw new IllegalArgumentException("Illegal consistency level " + this.serialConsistencyLevel + " for attribute serialConsistencyLevel");

            if (this.incrementBy == 0L)
                throw new IllegalArgumentException("Sequence must have non-zero increment - is " + this.incrementBy);
            if (this.maxValue <= this.minValue)
                throw new IllegalArgumentException("Sequence minVal " + this.minValue + " must be less than maxVal " + this.maxValue);
            if (this.startWith < this.minValue || this.startWith > this.maxValue)
                throw new IllegalArgumentException("Sequence startWith " + this.startWith + " must be between minVal " + this.minValue + " and maxVal " + this.maxValue);
            if (this.cache < 1L)
                throw new IllegalArgumentException("Sequence cache " + this.cache + " value must be greater or equal to 1");
            if (this.cacheLocal < 1L)
                throw new IllegalArgumentException("Sequence cacheLocal " + this.cache + " value must be greater or equal to 1");
            if (this.cacheLocal > this.cache)
                throw new IllegalArgumentException("Sequence cacheLocal " + this.cacheLocal + " must not be greater than cache " + this.cache);
            BigInteger possibleIncrements = BigInteger.valueOf(this.maxValue).subtract(BigInteger.valueOf(this.minValue)).add(BigInteger.ONE);
            possibleIncrements = possibleIncrements.divide(BigInteger.valueOf(this.incrementBy > 0 ? this.incrementBy : -this.incrementBy));
            if (possibleIncrements.compareTo(BigInteger.valueOf(this.cache)) < 0)
                throw new IllegalArgumentException("Sequence cache " + this.cache + " must not be greater than max possible increments " + possibleIncrements);
            if (possibleIncrements.compareTo(BigInteger.valueOf(this.cacheLocal)) < 0)
                throw new IllegalArgumentException("Sequence cacheLocal " + this.cacheLocal + " must not be greater than max possible increments " + possibleIncrements);

            return SequenceSession.this.create(this);
        }
    }
}
