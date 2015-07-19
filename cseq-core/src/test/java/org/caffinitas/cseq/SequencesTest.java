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
package org.caffinitas.cseq;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import org.caffinitas.cseq.nodeid.NetworkInterfaceNodeId;
import org.caffinitas.cseq.sequences.Sequence;
import org.caffinitas.cseq.sequences.SequenceSession;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * TODO add javadoc explaining the purpose of this class/interface/annotation
 */
public class SequencesTest
{
    @Test
    @CassandraVersion(major = 2.0)
    public void testSequences20() throws Exception
    {
        testSequences();
    }

    @Test
    @CassandraVersion(major = 2.1)
    public void testSequences21() throws Exception
    {
        testSequences();
    }

    @Test
    @CassandraVersion(major = 2.2)
    public void testSequences22() throws Exception
    {
        testSequences();
    }

    private static void testSequences() throws Exception
    {
        CCMBridge ccm = null;
        Cluster cluster = null;

        try
        {
            ccm = CCMBridge.create("test", 1);
            cluster = Cluster.builder().addContactPoint(CCMBridge.ipOfNode(1)).build();
            Session session = cluster.connect();
            session.execute("create keyspace ks WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");

            SequenceSession sequenceSession = new SequenceSession(session, "ks", NetworkInterfaceNodeId.any());
            try (Sequence seq = sequenceSession.builder()
                                               .name("seq_1_1")
                                               .create())
            {
                for (int i = 1; i <= 10000; i++)
                    assertEquals(seq.nextval(100000L), i);
            }

            try (Sequence seq = sequenceSession.builder()
                                               .name("seq_100_100")
                                               .cache(100)
                                               .cacheLocal(100)
                                               .create())
            {
                for (int i = 1; i <= 10000; i++)
                    assertEquals(seq.nextval(100000L), i);
            }

            try (Sequence seq = sequenceSession.builder()
                                               .name("seq_100_10")
                                               .cache(100)
                                               .cacheLocal(10)
                                               .create())
            {
                for (int i = 1; i <= 10000; i++)
                    assertEquals(seq.nextval(100000L), i);
            }

            try (Sequence seq = sequenceSession.builder()
                                               .name("seq_50_30")
                                               .cache(100)
                                               .cacheLocal(10)
                                               .create())
            {
                for (int i = 1; i <= 10000; i++)
                    assertEquals(seq.nextval(100000L), i);
            }

            int threads = Math.min(Runtime.getRuntime().availableProcessors(), 8);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try
            {
                concurrentTest(session, threads, pool, "seq_1_1", 500);
                concurrentTest(session, threads, pool, "seq_100_100", 10000);
                concurrentTest(session, threads, pool, "seq_100_10", 10000);
                concurrentTest(session, threads, pool, "seq_50_30", 10000);
            }
            finally
            {
                pool.shutdown();
            }
        }
        finally
        {
            if (cluster != null)
                cluster.close();
            if (ccm != null)
                ccm.remove();
        }
    }

    private static void concurrentTest(Session session, int threads, ExecutorService pool, String seqName, int count) throws InterruptedException, java.util.concurrent.ExecutionException
    {
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger nodeIdSeq = new AtomicInteger();
        List<Future<Set<Long>>> futures = new ArrayList<>(threads);
        for (int thr = 0; thr < threads; thr++)
            futures.add(pool.submit(() -> {
                latch.countDown();

                Set<Long> ids = new HashSet<>();
                SequenceSession seqSess = new SequenceSession(session, "ks", new TestNodeId(nodeIdSeq.incrementAndGet()));
                try (Sequence seq = seqSess.open(seqName))
                {
                    for (int i = 1; i <= count; i++)
                        ids.add(seq.nextval(100000L));
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }

                return ids;
            }));

        Set<Long> allIds = new HashSet<>();
        for (Future<Set<Long>> f : futures)
        {
            Set<Long> ids = f.get();
            assertEquals(count, ids.size());
            int sz = allIds.size();
            allIds.addAll(ids);
            assertEquals(allIds.size(), sz + ids.size());
        }
        assertEquals(allIds.size(), count * threads);
    }
}
