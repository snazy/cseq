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

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.Row;

/**
 * TODO add javadoc explaining the purpose of this class/interface/annotation
 */
public class Sequence implements AutoCloseable
{
    private static final Logger logger = LoggerFactory.getLogger(Sequence.class);

    final String name;
    final long startWith;
    final long incrementBy;
    final long minValue;
    final long maxValue;
    final long cache;
    final long cacheLocal;
    final ConsistencyLevel consistencyLevel;
    final ConsistencyLevel serialConsistencyLevel;
    final SequenceSession sequenceSession;

    private final Lock lock = new ReentrantLock();
    private boolean shutdown;
    /**
     * Flag whether a row in {@code system.sequence_local} exists.
     */
    private boolean peerPersisted;
    private final Range peerRange = new Range();
    private final Range vmRange = new Range();

    public Sequence(SequenceSession sequenceSession, String name, long startWith, long incrementBy, long minValue, long maxValue, long cache, long cacheLocal, ConsistencyLevel consistencyLevel, ConsistencyLevel serialConsistencyLevel)
    {
        this.sequenceSession = sequenceSession;
        this.name = name;
        this.startWith = startWith;
        this.incrementBy = incrementBy;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.cache = cache;
        this.cacheLocal = cacheLocal;
        this.consistencyLevel = consistencyLevel;
        this.serialConsistencyLevel = serialConsistencyLevel;

        LongPair nextEnd = nextLocalCached();
        if (nextEnd != null)
        {
            vmRange.set(nextEnd.next, nextEnd.end);
            peerRange.set(nextEnd.next, nextEnd.end);
            peerRange.avail = false;
            removeLocalCached();
            peerPersisted = false;
            logger.info("Sequence {} initialized with peer-local cache {}", name, vmRange);
        }
        else
            logger.info("Sequence {} initialized", name);
    }

    public void close() throws Exception
    {
        lock.lock();
        try
        {
            if (shutdown)
                return;

            shutdown = true;

            logger.debug("Shutting down sequence {}", name);
            if (vmRange.avail && cacheLocal > 1L)
            {
                logger.info("Persisting sequence {} peer-local cache with {}", name, vmRange);

                // update system.sequence_local with vmNext..peerEnd to not loose already allocated range
                updateLocalCached(vmRange.next, peerRange.end);
            }

            sequenceSession.sequenceClosed(this);
        }
        finally
        {
            lock.unlock();
        }
    }

    public long nextval(long timeoutMicros) throws TimeoutException
    {
        logger.debug("Acquiring nextval for sequence {}", this);

        try
        {
            if (!lock.tryLock(timeoutMicros, TimeUnit.MICROSECONDS))
                throw new TimeoutException();
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
        try
        {
            assert !shutdown;

            if (vmRange.avail)
            {
                return nextvalFromVm();
            }

            if (!peerRange.avail)
            {
                // allocate a new peer-range

                logger.trace("Acquiring peer range for sequence {}", name);

                peerRange.update(nextPeerRange(), cache, maxValue, minValue);

                logger.trace("Acquired peer range {} for sequence {}", peerRange, name);
            }

            if (!vmRange.avail)
            {
                // allocate a new JVM cached range from current peer-range

                logger.trace("Updating JVM cached range for sequence {}", name);

                vmRange.pull(peerRange, cacheLocal, peerRange.end);

                peerRange.next = boundedAdd(vmRange.end, incrementBy, peerRange.end, peerRange.end);

                updateLocal();
            }

            return nextvalFromVm();
        }
        finally
        {
            lock.unlock();
        }
    }

    private LongPair nextLocalCached()
    {
        Row row = sequenceSession.session.execute(sequenceSession.pstmtSelectSequenceLocal.bind(name,
                                                                                                sequenceSession.nodeId.nodeId()))
                                         .one();

        return row == null ? null : new LongPair(row.getLong(0), row.getLong(1));
    }

    private void removeLocalCached()
    {
        sequenceSession.session.execute(sequenceSession.pstmtRemoveSequenceLocal.bind(name,
                                                                                      sequenceSession.nodeId.nodeId()));
    }

    private void updateLocalCached(long nextVal, long reserved)
    {
        sequenceSession.session.execute(sequenceSession.pstmtUpdateSequenceLocal.bind(nextVal,
                                                                                      reserved,
                                                                                      name,
                                                                                      sequenceSession.nodeId.nodeId()));
    }

    private void updateLocal()
    {
        if (cacheLocal == 1L)
            return;

        if (!peerRange.avail)
        {
            logger.debug("Peer-local cached range exhausted for sequence {}", name);
            if (peerPersisted)
            {
                removeLocalCached();
                peerPersisted = false;
            }
        }
        else
        {
            logger.trace("Updating peer-local cached range for sequence {} to peerRange={}", name, peerRange);
            updateLocalCached(peerRange.next, peerRange.end);
            peerPersisted = true;
        }
    }

    private long nextvalFromVm()
    {
        logger.trace("Acquired local range {} for sequence {}", vmRange, name);

        long next = vmRange.pull();

        logger.trace("Acquire nextval for sequence {} returns {} (vmRange={}, peerRange={})", name, next, vmRange, peerRange);
        return next;
    }

    long peerAdder(long value)
    {

        // TODO exhausted-exception is thrown too early - e.g. INCREMENT BY 1 ... MAXVALUE 3 does only get values 1 + 2 - exhausted-exception is thrown for nextval that would return 3

        long r = value + cache * incrementBy;

        if (incrementBy > 0)
        {

            // positive

            if (r < value || r > maxValue)
            {
                // integer overflow
                throw new SequenceExhaustedException("Sequence " + name + " exhausted");
            }

            return r;
        }

        // negative

        if (r > value || r < minValue)
        {
            // integer overflow
            throw new SequenceExhaustedException("Sequence " + name + " exhausted");
        }

        return r;
    }

    long nextPeerRange()
    {
        while (true)
        {
            // TODO this is a very expensive CAS operation

            Row row = sequenceSession.session.execute(sequenceSession.pstmtSelectSequencePeer.bind(name))
                                             .one();
            if (row == null)
                throw new IllegalStateException("Sequence " + name + " no longer exists");

            long nextVal = row.getLong(0);
            boolean exhausted = row.getBool(1);
            if (exhausted)
                throw new IllegalStateException("Sequence " + name + " exhausted");
            long incrementedVal;
            try
            {
                incrementedVal = peerAdder(nextVal);
            }
            catch (SequenceExhaustedException exh)
            {
                incrementedVal = nextVal;
                exhausted = true;
            }

            boolean applied = sequenceSession.session.execute(sequenceSession.pstmtUpdateSequencePeer.bind(incrementedVal,
                                                                                                           exhausted,
                                                                                                           name,
                                                                                                           nextVal)
                                                                                                     .setSerialConsistencyLevel(serialConsistencyLevel))
                                                     .wasApplied();
            if (applied)
                return nextVal;

            try
            {
                // TODO add some better backoff
                Thread.sleep(ThreadLocalRandom.current().nextLong(200L));
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    @VisibleForTesting
    public long boundedAdd(long val, long add, long positiveMax, long negativeMin)
    {
        long n = val + add;

        if (incrementBy > 0)
        {
            if (n < val || n > positiveMax)
            {
                // prevent overflow
                n = positiveMax;
            }
            else
            {
                n = Math.min(n, positiveMax);
            }
        }
        else
        {
            if (n > val || n < negativeMin)
            {
                // prevent overflow
                n = negativeMin;
            }
            else
            {
                n = Math.max(n, negativeMin);
            }
        }
        return n;
    }

    final class Range
    {
        private long next;
        private long end;
        private boolean avail;

//        void reset()
//        {
//            next = end = 0L;
//            avail = false;
//        }

        void set(long next, long end)
        {
            this.next = next;
            this.end = end;
            this.avail = true;
        }

        void update(long next, long count, long positiveMax, long negativeMin)
        {
            this.next = next;
            this.end = boundedAdd(next, (count - 1) * incrementBy, positiveMax, negativeMin);
            this.avail = true;
        }

        public String toString()
        {
            return next + ".." + end + '(' + avail + ')';
        }

        long pull()
        {
            assert avail;

            long r = next;
            if (r == end)
                avail = false;
            else
                next += incrementBy;
            return r;
        }

        void pull(Range source, long count, long end)
        {
            assert source.avail;

            this.next = source.next;
            this.end = boundedAdd(this.next, (count - 1) * incrementBy, end, end);
            if (this.end == end)
                source.avail = false;
            else
                source.next = this.end + incrementBy;
            this.avail = true;
        }
    }
}
