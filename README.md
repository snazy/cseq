Sequences and locks for Apache Cassandra
========================================

CSeq provides an implementation for sequences and distribtued locks using
Apache Cassandra light-weight-transactions.

Designed to work with Datastax Java Driver 2.2.0-rc1+ against Apache Cassandra 2.1, 2.0 using Java 8.

Status
======

Current status of this project is: **WORK IN PROGRESS**

Sequences
=========

Sequences in the relational database world provide a generator for unique numbers. The concept may work fine
in the relational world with a _single_ database server - but not really in a distributed database.

But some people require a mechanism to generate cluster-wide unique numbers.

Please keep in mind, that sequences are not meant to provide a _strictly increasing_ (or decreasing) stream of
numbers - neither in the relational world nor in this implementation. A sequence guarantees to generate
globally unique values.

A sequence generator is often used to generate unique values to be used in primary keys. This is often a bad approach
because although sequences can generate potentially large numbers (e.g. 1 to 1,000,000), that range will often be
exceeded. A potentially valid approach to deal with an exhausted sequence, is to extend the possible range
(e.g. from 1..1,000,000 to 1..2,000,000) - but just moving the _end-of-life_ a bit into the future.
But more important is that your application will run into errors when the sequence hits the end of the permitted range.

TL;DR take care when using sequences. Sequences are a bad choice to generate unique numbers for primary keys.

Sequences can be a perfectly valid approach when you have to deal with limited ranges anyway - for example as
a network card manufacturer you get a range of Ethernet addresses, that naturally must not be exceeded.

Consistency Levels
------------------

The (default) consistency levels used for sequences are ``SERIAL`` and ``QUORUM``. Be very careful in a multi-DC
configuration with sequences in combination with ``LOCAL_SERIAL`` and/or ``LOCAL_QUORUM`` - sequences might return
duplicate values in combination with ``LOCAL_`` consistency levels and CLs less then ``QUORUM``.

Background
----------

The Apache Cassandra JIRA ticket [CASSANDRA-9200](https://issues.apache.org/jira/browse/CASSANDRA-9200) covers
the idea to add a generator for cluster-wide unique numbers. While working on that ticket and discussing with
others, it turned out that the proposed implementation is not what we want to provide in Cassandra due to the
restrictions mentioned above.

Locks
=====

TBD
