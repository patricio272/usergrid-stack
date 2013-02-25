/*******************************************************************************
 * Copyright 2012 Apigee Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.usergrid.mq.cassandra;

import static me.prettyprint.hector.api.factory.HFactory.createColumn;
import static me.prettyprint.hector.api.factory.HFactory.createCounterSliceQuery;
import static me.prettyprint.hector.api.factory.HFactory.createMutator;
import static me.prettyprint.hector.api.factory.HFactory.createSliceQuery;
import static org.usergrid.mq.Queue.QUEUE_CREATED;
import static org.usergrid.mq.Queue.QUEUE_MODIFIED;
import static org.usergrid.mq.Queue.QUEUE_NEWEST;
import static org.usergrid.mq.Queue.QUEUE_OLDEST;
import static org.usergrid.mq.Queue.getQueueId;
import static org.usergrid.mq.Queue.normalizeQueuePath;
import static org.usergrid.mq.QueuePosition.CONSUMER;
import static org.usergrid.mq.QueuePosition.END;
import static org.usergrid.mq.QueuePosition.LAST;
import static org.usergrid.mq.QueuePosition.START;
import static org.usergrid.mq.cassandra.CassandraMQUtils.addMessageToMutator;
import static org.usergrid.mq.cassandra.CassandraMQUtils.addQueueToMutator;
import static org.usergrid.mq.cassandra.CassandraMQUtils.deserializeMessage;
import static org.usergrid.mq.cassandra.CassandraMQUtils.deserializeQueue;
import static org.usergrid.mq.cassandra.CassandraMQUtils.getQueueShardRowKey;
import static org.usergrid.mq.cassandra.QueueIndexUpdate.indexValueCode;
import static org.usergrid.mq.cassandra.QueueIndexUpdate.toIndexableValue;
import static org.usergrid.mq.cassandra.QueueIndexUpdate.validIndexableValue;
import static org.usergrid.mq.cassandra.QueueIndexUpdate.validIndexableValueOrJson;
import static org.usergrid.mq.cassandra.QueuesCF.COUNTERS;
import static org.usergrid.mq.cassandra.QueuesCF.MESSAGE_PROPERTIES;
import static org.usergrid.mq.cassandra.QueuesCF.PROPERTY_INDEX;
import static org.usergrid.mq.cassandra.QueuesCF.PROPERTY_INDEX_ENTRIES;
import static org.usergrid.mq.cassandra.QueuesCF.QUEUE_DICTIONARIES;
import static org.usergrid.mq.cassandra.QueuesCF.QUEUE_INBOX;
import static org.usergrid.mq.cassandra.QueuesCF.QUEUE_PROPERTIES;
import static org.usergrid.mq.cassandra.QueuesCF.QUEUE_SUBSCRIBERS;
import static org.usergrid.mq.cassandra.QueuesCF.QUEUE_SUBSCRIPTIONS;
import static org.usergrid.persistence.Schema.DICTIONARY_COUNTERS;
import static org.usergrid.persistence.cassandra.ApplicationCF.APPLICATION_AGGREGATE_COUNTERS;
import static org.usergrid.persistence.cassandra.CassandraPersistenceUtils.batchExecute;
import static org.usergrid.persistence.cassandra.CassandraPersistenceUtils.key;
import static org.usergrid.persistence.cassandra.CassandraService.RETRY_COUNT;
import static org.usergrid.utils.CompositeUtils.setEqualityFlag;
import static org.usergrid.utils.CompositeUtils.setGreaterThanEqualityFlag;
import static org.usergrid.utils.ConversionUtils.bytebuffer;
import static org.usergrid.utils.IndexUtils.getKeyValueList;
import static org.usergrid.utils.MapUtils.emptyMapWithKeys;
import static org.usergrid.utils.NumberUtils.roundLong;
import static org.usergrid.utils.UUIDUtils.getTimestampInMicros;
import static org.usergrid.utils.UUIDUtils.newTimeUUID;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import me.prettyprint.cassandra.serializers.ByteBufferSerializer;
import me.prettyprint.cassandra.serializers.BytesArraySerializer;
import me.prettyprint.cassandra.serializers.DynamicCompositeSerializer;
import me.prettyprint.cassandra.serializers.LongSerializer;
import me.prettyprint.cassandra.serializers.StringSerializer;
import me.prettyprint.cassandra.serializers.UUIDSerializer;
import me.prettyprint.hector.api.Keyspace;
import me.prettyprint.hector.api.beans.AbstractComposite.ComponentEquality;
import me.prettyprint.hector.api.beans.ColumnSlice;
import me.prettyprint.hector.api.beans.CounterRow;
import me.prettyprint.hector.api.beans.CounterRows;
import me.prettyprint.hector.api.beans.CounterSlice;
import me.prettyprint.hector.api.beans.DynamicComposite;
import me.prettyprint.hector.api.beans.HColumn;
import me.prettyprint.hector.api.beans.HCounterColumn;
import me.prettyprint.hector.api.factory.HFactory;
import me.prettyprint.hector.api.mutation.Mutator;
import me.prettyprint.hector.api.query.MultigetSliceCounterQuery;
import me.prettyprint.hector.api.query.QueryResult;
import me.prettyprint.hector.api.query.SliceCounterQuery;
import me.prettyprint.hector.api.query.SliceQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usergrid.mq.CounterQuery;
import org.usergrid.mq.Message;
import org.usergrid.mq.Query;
import org.usergrid.mq.Query.CounterFilterPredicate;
import org.usergrid.mq.QueryProcessor;
import org.usergrid.mq.QueryProcessor.QuerySlice;
import org.usergrid.mq.Queue;
import org.usergrid.mq.QueueManager;
import org.usergrid.mq.QueueQuery;
import org.usergrid.mq.QueueResults;
import org.usergrid.mq.QueueSet;
import org.usergrid.mq.QueueSet.QueueInfo;
import org.usergrid.mq.cassandra.QueueIndexUpdate.QueueIndexEntry;
import org.usergrid.mq.cassandra.io.BooleanSearch;
import org.usergrid.mq.cassandra.io.ConsumerTransaction;
import org.usergrid.mq.cassandra.io.EndSearch;
import org.usergrid.mq.cassandra.io.NoTransactionSearch;
import org.usergrid.mq.cassandra.io.QueueSearch;
import org.usergrid.mq.cassandra.io.StartSearch;
import org.usergrid.persistence.AggregateCounter;
import org.usergrid.persistence.AggregateCounterSet;
import org.usergrid.persistence.CounterResolution;
import org.usergrid.persistence.Results;
import org.usergrid.persistence.cassandra.CassandraPersistenceUtils;
import org.usergrid.persistence.cassandra.CassandraService;
import org.usergrid.persistence.cassandra.CounterUtils;
import org.usergrid.persistence.cassandra.CounterUtils.AggregateCounterSelection;

import com.fasterxml.uuid.UUIDComparator;

public class QueueManagerImpl implements QueueManager {

  public static final Logger logger = LoggerFactory.getLogger(QueueManagerImpl.class);

  public static final String DICTIONARY_SUBSCRIBER_INDEXES = "subscriber_indexes";
  public static final String DICTIONARY_MESSAGE_INDEXES = "message_indexes";

  public static final int QUEUE_SHARD_INTERVAL = 1000 * 60 * 60 * 24;
  public static final int INDEX_ENTRY_LIST_COUNT = 1000;

  public static final int DEFAULT_SEARCH_COUNT = 10000;
  public static final int ALL_COUNT = 100000000;

  UUID applicationId;
  QueueManagerFactoryImpl mmf;
  CassandraService cass;
  CounterUtils counterUtils;

  public static final StringSerializer se = new StringSerializer();
  public static final ByteBufferSerializer be = new ByteBufferSerializer();
  public static final UUIDSerializer ue = new UUIDSerializer();
  public static final BytesArraySerializer bae = new BytesArraySerializer();
  public static final DynamicCompositeSerializer dce = new DynamicCompositeSerializer();
  public static final LongSerializer le = new LongSerializer();

  public QueueManagerImpl() {
  }

  public QueueManagerImpl init(QueueManagerFactoryImpl mmf, CassandraService cass, CounterUtils counterUtils,
      UUID applicationId) {
    this.mmf = mmf;
    this.cass = cass;
    this.counterUtils = counterUtils;
    this.applicationId = applicationId;
    return this;
  }

  @Override
  public Message getMessage(UUID messageId) {
    SliceQuery<UUID, String, ByteBuffer> q = createSliceQuery(cass.getApplicationKeyspace(applicationId), ue, se, be);
    q.setColumnFamily(MESSAGE_PROPERTIES.getColumnFamily());
    q.setKey(messageId);
    q.setRange(null, null, false, ALL_COUNT);
    QueryResult<ColumnSlice<String, ByteBuffer>> r = q.execute();
    ColumnSlice<String, ByteBuffer> slice = r.get();
    List<HColumn<String, ByteBuffer>> results = slice.getColumns();
    return deserializeMessage(results);
  }

  public Message batchPostToQueue(Mutator<ByteBuffer> batch, String queuePath, Message message,
      MessageIndexUpdate indexUpdate, long timestamp) {

    queuePath = normalizeQueuePath(queuePath);
    UUID queueId = getQueueId(queuePath);

    message.sync();

    addMessageToMutator(batch, message, timestamp);

    long shard_ts = roundLong(message.getTimestamp(), QUEUE_SHARD_INTERVAL);

    batch.addInsertion(getQueueShardRowKey(queueId, shard_ts), QUEUE_INBOX.getColumnFamily(),
        createColumn(message.getUuid(), ByteBuffer.allocate(0), timestamp, ue, be));

    long oldest_ts = Long.MAX_VALUE - getTimestampInMicros(message.getUuid());
    batch.addInsertion(bytebuffer(queueId), QUEUE_PROPERTIES.getColumnFamily(),
        createColumn(QUEUE_OLDEST, message.getUuid(), oldest_ts, se, ue));

    long newest_ts = getTimestampInMicros(message.getUuid());
    batch.addInsertion(bytebuffer(queueId), QUEUE_PROPERTIES.getColumnFamily(),
        createColumn(QUEUE_NEWEST, message.getUuid(), newest_ts, se, ue));

    batch.addInsertion(bytebuffer(getQueueId("/")), QUEUE_SUBSCRIBERS.getColumnFamily(),
        createColumn(queuePath, queueId, timestamp, se, ue));

    counterUtils.batchIncrementQueueCounter(batch, getQueueId("/"), queuePath, 1L, timestamp, applicationId);

    if (indexUpdate == null) {
      indexUpdate = new MessageIndexUpdate(message);
    }
    indexUpdate.addToMutation(batch, queueId, shard_ts, timestamp);

    counterUtils.addMessageCounterMutations(batch, applicationId, queueId, message, timestamp);

    batch.addInsertion(bytebuffer(queueId), QUEUE_PROPERTIES.getColumnFamily(),
        createColumn(QUEUE_CREATED, timestamp / 1000, Long.MAX_VALUE - timestamp, se, le));

    batch.addInsertion(bytebuffer(queueId), QUEUE_PROPERTIES.getColumnFamily(),
        createColumn(QUEUE_MODIFIED, timestamp / 1000, timestamp, se, le));

    return message;
  }

  @Override
  public Message postToQueue(String queuePath, Message message) {
    long timestamp = cass.createTimestamp();
    Mutator<ByteBuffer> batch = createMutator(cass.getApplicationKeyspace(applicationId), be);

    queuePath = normalizeQueuePath(queuePath);

    MessageIndexUpdate indexUpdate = new MessageIndexUpdate(message);

    batchPostToQueue(batch, queuePath, message, indexUpdate, timestamp);

    batchExecute(batch, RETRY_COUNT);

    String firstSubscriberQueuePath = null;
    while (true) {

      QueueSet subscribers = getSubscribers(queuePath, firstSubscriberQueuePath, 1000);

      if (subscribers.getQueues().isEmpty()) {
        break;
      }

      batch = createMutator(cass.getApplicationKeyspace(applicationId), be);
      for (QueueInfo q : subscribers.getQueues()) {
        batchPostToQueue(batch, q.getPath(), message, indexUpdate, timestamp);

        firstSubscriberQueuePath = q.getPath();
      }
      batchExecute(batch, RETRY_COUNT);

      if (!subscribers.hasMore()) {
        break;
      }
    }

    return message;
  }

  @Override
  public List<Message> postToQueue(String queuePath, List<Message> messages) {

    // Can't do this as one big batch operation because it will
    // time out

    for (Message message : messages) {
      postToQueue(queuePath, message);
    }

    return messages;
  }



  static TreeSet<UUID> add(TreeSet<UUID> a, UUID uuid, boolean reversed, int limit) {

    if (a == null) {
      a = new TreeSet<UUID>(new UUIDComparator());
    }

    if (uuid == null) {
      return a;
    }

    // if we have less than the limit, just add it
    if (a.size() < limit) {
      a.add(uuid);
    } else if (reversed) {
      // if reversed, we want to add more recent messages
      // and eject the oldest
      if (UUIDComparator.staticCompare(uuid, a.first()) > 0) {
        a.pollFirst();
        a.add(uuid);
      }
    } else {
      // add older messages and eject the newset
      if (UUIDComparator.staticCompare(uuid, a.last()) < 0) {
        a.pollLast();
        a.add(uuid);
      }
    }

    return a;
  }

  static TreeSet<UUID> add(TreeSet<UUID> a, TreeSet<UUID> b, boolean reversed, int limit) {

    if (b == null) {
      return a;
    }

    for (UUID uuid : b) {
      a = add(a, uuid, reversed, limit);
    }

    return a;
  }

  static TreeSet<UUID> mergeOr(TreeSet<UUID> a, TreeSet<UUID> b, boolean reversed, int limit) {
    TreeSet<UUID> mergeSet = new TreeSet<UUID>(new UUIDComparator());

    if ((a == null) && (b == null)) {
      return mergeSet;
    } else if (a == null) {
      return b;
    } else if (b == null) {
      return a;
    }

    add(mergeSet, a, reversed, limit);
    add(mergeSet, b, reversed, limit);

    return mergeSet;
  }

  static TreeSet<UUID> mergeAnd(TreeSet<UUID> a, TreeSet<UUID> b, boolean reversed, int limit) {
    TreeSet<UUID> mergeSet = new TreeSet<UUID>(new UUIDComparator());

    if (a == null) {
      return mergeSet;
    }
    if (b == null) {
      return mergeSet;
    }

    for (UUID uuid : b) {
      if (a.contains(b)) {
        add(mergeSet, uuid, reversed, limit);
      }
    }

    return mergeSet;
  }

  @Override
  public QueueResults getFromQueue(String queuePath, QueueQuery query) {

    if (query == null) {
      query = new QueueQuery();
    }

    Keyspace ko = cass.getApplicationKeyspace(applicationId);


    QueueSearch search = null;

    if (query.getPosition() == LAST || query.getPosition() == CONSUMER) {
      if (!query.hasFilterPredicates()) {
        if (query.getTimeout() > 0) {
          search = new ConsumerTransaction(ko, cass.createTimestamp());
        } else {
          search = new NoTransactionSearch(ko, cass.createTimestamp());
        }
      } else {
        search = new BooleanSearch();
      }
    } else if (query.getPosition() == START) {

      search = new StartSearch(ko, cass.createTimestamp());
    } else if (query.getPosition() == END) {
      search = new EndSearch(ko, cass.createTimestamp());

    }
    
    return search.getResults(queuePath, query);

    // UUID searchConsumerId = null;
    //
    // if (query.getPosition() == LAST) {
    // searchConsumerId = queueId;
    //
    // if ((query.getLastTimestamp() == 0) && (start_uuid == null)) {
    //
    // start_uuid = getConsumerQueuePosition(ko, queueId, queueId);
    // skip_first = (start_uuid != null);
    // }
    // } else if (query.getPosition() == CONSUMER) {
    // searchConsumerId = consumerId;
    // if ((query.getLastTimestamp() == 0) && (start_uuid == null)) {
    // start_uuid = getConsumerQueuePosition(ko, consumerId, queueId);
    // skip_first = (start_uuid != null);
    // }
    // } else if (query.getPosition() == QueuePosition.START) {
    // start_uuid = null;
    //
    // next_count = max(next_count, limit);
    // } else if (query.getPosition() == QueuePosition.END) {
    // start_uuid = null;
    //
    // prev_count = max(prev_count, limit);
    // }
    //
    // QueueBounds bounds = getQueueBounds(ko, queueId);
    //
    // TreeSet<UUID> uuid_set = new TreeSet<UUID>(new UUIDComparator());

//    QueueSearch search = null;
//
//    if (!query.hasFilterPredicates()) {
      // if (prev_count > 0) {
      //
      //
      //
      // uuid_set = getQueueRange(ko, queueId, bounds, uuid_set,
      // start_uuid, null, true, prev_count);
      // }
      //
      // uuid_set = getQueueRange(ko, queueId, bounds, uuid_set, start_uuid,
      // null, false, skip_first ? next_count + 1 : next_count);
//
//      if (query.getTimeout() > 0) {
//        search = new ConsumerTransaction();
//      } else {
//        search = new NoTransaction();
//      }
//
//    } else {
//
//      search = new BooleanSearch();
      //
      // QueryProcessor qp = new QueryProcessor(query);
      // List<QuerySlice> slices = qp.getSlices();
      //
      // TreeSet<UUID> prev_set = null;
      //
      // if (prev_count > 0) {
      // if (slices.size() > 1) {
      // prev_count = DEFAULT_SEARCH_COUNT;
      // }
      //
      // for (QuerySlice slice : slices) {
      //
      // TreeSet<UUID> r = null;
      // try {
      // r = searchQueueRange(ko, queueId, bounds, null,
      // start_uuid, null, true, slice, prev_count);
      // } catch (Exception e) {
      // logger.error("Error during search", e);
      // }
      //
      // if (prev_set != null) {
      // mergeAnd(prev_set, r, true, 10000);
      // } else {
      // prev_set = r;
      // }
      //
      // }
      // }
      //
      // TreeSet<UUID> next_set = null;
      //
      // if (slices.size() > 1) {
      // next_count = DEFAULT_SEARCH_COUNT;
      // }
      //
      // for (QuerySlice slice : slices) {
      //
      // TreeSet<UUID> r = null;
      // try {
      // r = searchQueueRange(ko, queueId, bounds, null, start_uuid,
      // null, false, slice, skip_first ? next_count + 1
      // : next_count);
      // } catch (Exception e) {
      // logger.error("Error during search", e);
      // }
      //
      // if (next_set != null) {
      // mergeAnd(next_set, r, true, 10000);
      // } else {
      // next_set = r;
      // }
      //
      // }

//    }
//
//    if (skip_first && query.isUpdate()) {
//      uuid_set.remove(start_uuid);
//    }
//
//    List<UUID> uuid_list = new ArrayList<UUID>(uuid_set);
//    UUIDUtils.sortReversed(uuid_list);
//    limit = query.getLimit();
//    if ((limit > 0) && (limit < uuid_list.size())) {
//      uuid_list = uuid_list.subList(0, limit);
//    }
//
//    Rows<UUID, String, ByteBuffer> messageResults = createMultigetSliceQuery(ko, ue, se, be)
//        .setColumnFamily(MESSAGE_PROPERTIES.getColumnFamily()).setKeys(uuid_list)
//        .setRange(null, null, false, ALL_COUNT).execute().get();
//
//    List<Message> messages = new ArrayList<Message>();
//    for (Row<UUID, String, ByteBuffer> row : messageResults) {
//      Message message = deserializeMessage(row.getColumnSlice().getColumns());
//      if (message != null) {
//        messages.add(message);
//      }
//    }
//
//    Message.sortReversed(messages);
//
//    UUID lastUUID = null;
//    if (messages.size() > 0) {
//      lastUUID = messages.get(0).getUuid();
//    } else if (start_uuid != null) {
//      lastUUID = start_uuid;
//    }
//
//    if (lastUUID != null && query.isUpdate()) {
//      Mutator<UUID> batch = createMutator(ko, ue);
//
//      if (query.getPosition() == QueuePosition.LAST) {
//        batch.addInsertion(queueId, CONSUMERS.getColumnFamily(),
//            createColumn(queueId, lastUUID, cass.createTimestamp(), ue, ue));
//      } else if (query.getPosition() == QueuePosition.CONSUMER) {
//        batch.addInsertion(consumerId, CONSUMERS.getColumnFamily(),
//            createColumn(queueId, lastUUID, cass.createTimestamp(), ue, ue));
//      }
//
//      batchExecute(batch, RETRY_COUNT);
//    }
//
//    // return new QueueResults(queuePath, queueId, messages, lastUUID,
//    // consumerId);
//    //
//    return search.getResults(queuePath, queueId, searchConsumerId, query);
  }

  public void batchSubscribeToQueue(Mutator<ByteBuffer> batch, String publisherQueuePath, UUID publisherQueueId,
      String subscriberQueuePath, UUID subscriberQueueId, long timestamp) {

    batch.addInsertion(bytebuffer(publisherQueueId), QUEUE_SUBSCRIBERS.getColumnFamily(),
        createColumn(subscriberQueuePath, subscriberQueueId, timestamp, se, ue));

    batch.addInsertion(bytebuffer(subscriberQueueId), QUEUE_SUBSCRIPTIONS.getColumnFamily(),
        createColumn(publisherQueuePath, publisherQueueId, timestamp, se, ue));

  }

  @Override
  public QueueSet subscribeToQueue(String publisherQueuePath, String subscriberQueuePath) {

    publisherQueuePath = normalizeQueuePath(publisherQueuePath);
    UUID publisherQueueId = getQueueId(publisherQueuePath);

    subscriberQueuePath = normalizeQueuePath(subscriberQueuePath);
    UUID subscriberQueueId = getQueueId(subscriberQueuePath);

    UUID timestampUuid = newTimeUUID();
    long timestamp = getTimestampInMicros(timestampUuid);

    Mutator<ByteBuffer> batch = createMutator(cass.getApplicationKeyspace(applicationId), be);

    batchSubscribeToQueue(batch, publisherQueuePath, publisherQueueId, subscriberQueuePath, subscriberQueueId,
        timestamp);

    try {
      Queue queue = getQueue(subscriberQueuePath, subscriberQueueId);
      if (queue != null) {
        batchUpdateQueuePropertiesIndexes(batch, publisherQueueId, subscriberQueuePath, subscriberQueueId,
            queue.getProperties(), timestampUuid);
      }
    } catch (Exception e) {
      logger.error("Unable to update index", e);
    }

    batchExecute(batch, RETRY_COUNT);

    return new QueueSet().addQueue(subscriberQueuePath, subscriberQueueId);

  }

  public void batchUnsubscribeFromQueue(Mutator<ByteBuffer> batch, String publisherQueuePath, UUID publisherQueueId,
      String subscriberQueuePath, UUID subscriberQueueId, long timestamp) {

    batch.addDeletion(bytebuffer(publisherQueueId), QUEUE_SUBSCRIBERS.getColumnFamily(), subscriberQueuePath, se,
        timestamp);

    batch.addDeletion(bytebuffer(subscriberQueueId), QUEUE_SUBSCRIPTIONS.getColumnFamily(), publisherQueuePath, se,
        timestamp);

  }

  @Override
  public QueueSet unsubscribeFromQueue(String publisherQueuePath, String subscriberQueuePath) {

    publisherQueuePath = normalizeQueuePath(publisherQueuePath);
    UUID publisherQueueId = getQueueId(publisherQueuePath);

    subscriberQueuePath = normalizeQueuePath(subscriberQueuePath);
    UUID subscriberQueueId = getQueueId(subscriberQueuePath);

    UUID timestampUuid = newTimeUUID();
    long timestamp = getTimestampInMicros(timestampUuid);

    Mutator<ByteBuffer> batch = createMutator(cass.getApplicationKeyspace(applicationId), be);

    batchUnsubscribeFromQueue(batch, publisherQueuePath, publisherQueueId, subscriberQueuePath, subscriberQueueId,
        timestamp);

    try {
      Queue queue = getQueue(subscriberQueuePath, subscriberQueueId);

      batchUpdateQueuePropertiesIndexes(batch, publisherQueueId, subscriberQueuePath, subscriberQueueId,
          emptyMapWithKeys(queue.getProperties()), timestampUuid);
    } catch (Exception e) {
      logger.error("Unable to update index", e);
    }

    batchExecute(batch, RETRY_COUNT);

    return new QueueSet().addQueue(subscriberQueuePath, subscriberQueueId);
  }

  @Override
  public QueueSet getSubscribers(String publisherQueuePath, String firstSubscriberQueuePath, int limit) {

    UUID publisherQueueId = getQueueId(publisherQueuePath);

    Keyspace ko = cass.getApplicationKeyspace(applicationId);

    if (firstSubscriberQueuePath != null) {
      limit += 1;
    }

    List<HColumn<String, UUID>> columns = createSliceQuery(ko, ue, se, ue).setKey(publisherQueueId)
        .setColumnFamily(QUEUE_SUBSCRIBERS.getColumnFamily())
        .setRange(normalizeQueuePath(firstSubscriberQueuePath), null, false, limit + 1).execute().get().getColumns();

    QueueSet queues = new QueueSet();

    int count = Math.min(limit, columns.size());
    if (columns != null) {
      for (int i = firstSubscriberQueuePath != null ? 1 : 0; i < count; i++) {
        HColumn<String, UUID> column = columns.get(i);
        queues.addQueue(column.getName(), column.getValue());
      }
    }
    if (columns.size() > limit) {
      queues.setMore(true);
    }
    return queues;
  }

  @Override
  public QueueSet getSubscriptions(String subscriberQueuePath, String firstSubscriptionQueuePath, int limit) {

    UUID subscriberQueueId = getQueueId(subscriberQueuePath);

    Keyspace ko = cass.getApplicationKeyspace(applicationId);

    if (firstSubscriptionQueuePath != null) {
      limit += 1;
    }

    List<HColumn<String, UUID>> columns = createSliceQuery(ko, ue, se, ue).setKey(subscriberQueueId)
        .setColumnFamily(QUEUE_SUBSCRIPTIONS.getColumnFamily())
        .setRange(normalizeQueuePath(firstSubscriptionQueuePath), null, false, limit + 1).execute().get().getColumns();

    QueueSet queues = new QueueSet();

    int count = Math.min(limit, columns.size());
    if (columns != null) {
      for (int i = firstSubscriptionQueuePath != null ? 1 : 0; i < count; i++) {
        HColumn<String, UUID> column = columns.get(i);
        queues.addQueue(column.getName(), column.getValue());
      }
    }
    if (columns.size() > limit) {
      queues.setMore(true);
    }
    return queues;
  }

  @Override
  public QueueSet addSubscribersToQueue(String publisherQueuePath, List<String> subscriberQueuePaths) {

    publisherQueuePath = normalizeQueuePath(publisherQueuePath);
    UUID publisherQueueId = getQueueId(publisherQueuePath);

    UUID timestampUuid = newTimeUUID();
    long timestamp = getTimestampInMicros(timestampUuid);

    Mutator<ByteBuffer> batch = createMutator(cass.getApplicationKeyspace(applicationId), be);

    QueueSet queues = new QueueSet();

    for (String subscriberQueuePath : subscriberQueuePaths) {

      subscriberQueuePath = normalizeQueuePath(subscriberQueuePath);
      UUID subscriberQueueId = getQueueId(subscriberQueuePath);

      batchSubscribeToQueue(batch, publisherQueuePath, publisherQueueId, subscriberQueuePath, subscriberQueueId,
          timestamp);

      try {
        Queue queue = getQueue(subscriberQueuePath, subscriberQueueId);

        batchUpdateQueuePropertiesIndexes(batch, publisherQueueId, subscriberQueuePath, subscriberQueueId,
            queue.getProperties(), timestampUuid);
      } catch (Exception e) {
        logger.error("Unable to update index", e);
      }

      queues.addQueue(subscriberQueuePath, subscriberQueueId);
    }

    batchExecute(batch, RETRY_COUNT);

    return queues;
  }

  @Override
  public QueueSet removeSubscribersFromQueue(String publisherQueuePath, List<String> subscriberQueuePaths) {

    publisherQueuePath = normalizeQueuePath(publisherQueuePath);
    UUID publisherQueueId = getQueueId(publisherQueuePath);

    UUID timestampUuid = newTimeUUID();
    long timestamp = getTimestampInMicros(timestampUuid);

    Mutator<ByteBuffer> batch = createMutator(cass.getApplicationKeyspace(applicationId), be);

    QueueSet queues = new QueueSet();

    for (String subscriberQueuePath : subscriberQueuePaths) {

      subscriberQueuePath = normalizeQueuePath(subscriberQueuePath);
      UUID subscriberQueueId = getQueueId(subscriberQueuePath);

      batchUnsubscribeFromQueue(batch, publisherQueuePath, publisherQueueId, subscriberQueuePath, subscriberQueueId,
          timestamp);

      try {
        Queue queue = getQueue(subscriberQueuePath, subscriberQueueId);

        batchUpdateQueuePropertiesIndexes(batch, publisherQueueId, subscriberQueuePath, subscriberQueueId,
            emptyMapWithKeys(queue.getProperties()), timestampUuid);
      } catch (Exception e) {
        logger.error("Unable to update index", e);
      }

      queues.addQueue(subscriberQueuePath, subscriberQueueId);
    }

    batchExecute(batch, RETRY_COUNT);

    return queues;
  }

  @Override
  public QueueSet subscribeToQueues(String subscriberQueuePath, List<String> publisherQueuePaths) {

    subscriberQueuePath = normalizeQueuePath(subscriberQueuePath);
    UUID subscriberQueueId = getQueueId(subscriberQueuePath);

    UUID timestampUuid = newTimeUUID();
    long timestamp = getTimestampInMicros(timestampUuid);

    Mutator<ByteBuffer> batch = createMutator(cass.getApplicationKeyspace(applicationId), be);

    QueueSet queues = new QueueSet();

    for (String publisherQueuePath : publisherQueuePaths) {

      publisherQueuePath = normalizeQueuePath(publisherQueuePath);
      UUID publisherQueueId = getQueueId(publisherQueuePath);

      batchSubscribeToQueue(batch, publisherQueuePath, publisherQueueId, subscriberQueuePath, subscriberQueueId,
          timestamp);

      try {
        Queue queue = getQueue(subscriberQueuePath, subscriberQueueId);

        batchUpdateQueuePropertiesIndexes(batch, publisherQueueId, subscriberQueuePath, subscriberQueueId,
            queue.getProperties(), timestampUuid);
      } catch (Exception e) {
        logger.error("Unable to update index", e);
      }

      queues.addQueue(publisherQueuePath, publisherQueueId);
    }

    batchExecute(batch, RETRY_COUNT);

    return queues;
  }

  @Override
  public QueueSet unsubscribeFromQueues(String subscriberQueuePath, List<String> publisherQueuePaths) {

    subscriberQueuePath = normalizeQueuePath(subscriberQueuePath);
    UUID subscriberQueueId = getQueueId(subscriberQueuePath);

    UUID timestampUuid = newTimeUUID();
    long timestamp = getTimestampInMicros(timestampUuid);

    Mutator<ByteBuffer> batch = createMutator(cass.getApplicationKeyspace(applicationId), be);

    QueueSet queues = new QueueSet();

    for (String publisherQueuePath : publisherQueuePaths) {

      publisherQueuePath = normalizeQueuePath(publisherQueuePath);
      UUID publisherQueueId = getQueueId(publisherQueuePath);

      batchUnsubscribeFromQueue(batch, publisherQueuePath, publisherQueueId, subscriberQueuePath, subscriberQueueId,
          timestamp);

      try {
        Queue queue = getQueue(subscriberQueuePath, subscriberQueueId);

        batchUpdateQueuePropertiesIndexes(batch, publisherQueueId, subscriberQueuePath, subscriberQueueId,
            emptyMapWithKeys(queue.getProperties()), timestampUuid);
      } catch (Exception e) {
        logger.error("Unable to update index", e);
      }

      queues.addQueue(publisherQueuePath, publisherQueueId);
    }

    batchExecute(batch, RETRY_COUNT);

    return queues;
  }

  @Override
  public void incrementAggregateQueueCounters(String queuePath, String category, String counterName, long value) {
    long timestamp = cass.createTimestamp();
    Mutator<ByteBuffer> m = createMutator(cass.getApplicationKeyspace(applicationId), be);
    counterUtils.batchIncrementAggregateCounters(m, applicationId, null, null, getQueueId(queuePath), category,
        counterName, value, timestamp);
    batchExecute(m, CassandraService.RETRY_COUNT);
  }

  public AggregateCounterSet getAggregateCounters(UUID queueId, String category, String counterName,
      CounterResolution resolution, long start, long finish, boolean pad) {

    start = resolution.round(start);
    finish = resolution.round(finish);
    long expected_time = start;
    Keyspace ko = cass.getApplicationKeyspace(applicationId);
    SliceCounterQuery<String, Long> q = createCounterSliceQuery(ko, se, le);
    q.setColumnFamily(APPLICATION_AGGREGATE_COUNTERS.getColumnFamily());
    q.setRange(start, finish, false, ALL_COUNT);
    QueryResult<CounterSlice<Long>> r = q.setKey(
        counterUtils.getAggregateCounterRow(counterName, null, null, queueId, category, resolution)).execute();
    List<AggregateCounter> counters = new ArrayList<AggregateCounter>();
    for (HCounterColumn<Long> column : r.get().getColumns()) {
      AggregateCounter count = new AggregateCounter(column.getName(), column.getValue());
      if (pad && !(resolution == CounterResolution.ALL)) {
        while (count.getTimestamp() != expected_time) {
          counters.add(new AggregateCounter(expected_time, 0));
          expected_time = resolution.next(expected_time);
        }
        expected_time = resolution.next(expected_time);
      }
      counters.add(count);
    }
    if (pad && !(resolution == CounterResolution.ALL)) {
      while (expected_time <= finish) {
        counters.add(new AggregateCounter(expected_time, 0));
        expected_time = resolution.next(expected_time);
      }
    }
    return new AggregateCounterSet(counterName, queueId, category, counters);
  }

  public List<AggregateCounterSet> getAggregateCounters(UUID queueId, CounterQuery query) throws Exception {

    CounterResolution resolution = query.getResolution();
    if (resolution == null) {
      resolution = CounterResolution.ALL;
    }
    long start = query.getStartTime() != null ? query.getStartTime() : 0;
    long finish = query.getFinishTime() != null ? query.getFinishTime() : 0;
    boolean pad = query.isPad();
    if (start <= 0) {
      start = 0;
    }
    if ((finish <= 0) || (finish < start)) {
      finish = System.currentTimeMillis();
    }
    start = resolution.round(start);
    finish = resolution.round(finish);
    long expected_time = start;

    if (pad && (resolution != CounterResolution.ALL)) {
      long max_counters = (finish - start) / resolution.interval();
      if (max_counters > 1000) {
        finish = resolution.round(start + (resolution.interval() * 1000));
      }
    }

    List<CounterFilterPredicate> filters = query.getCounterFilters();
    if (filters == null) {
      return null;
    }
    Map<String, org.usergrid.persistence.cassandra.CounterUtils.AggregateCounterSelection> selections = new HashMap<String, AggregateCounterSelection>();
    Keyspace ko = cass.getApplicationKeyspace(applicationId);

    for (CounterFilterPredicate filter : filters) {
      AggregateCounterSelection selection = new AggregateCounterSelection(filter.getName(), null, null, queueId,
          filter.getCategory());
      selections.put(selection.getRow(resolution), selection);
    }

    MultigetSliceCounterQuery<String, Long> q = HFactory.createMultigetSliceCounterQuery(ko, se, le);
    q.setColumnFamily(APPLICATION_AGGREGATE_COUNTERS.getColumnFamily());
    q.setRange(start, finish, false, ALL_COUNT);
    QueryResult<CounterRows<String, Long>> rows = q.setKeys(selections.keySet()).execute();

    List<AggregateCounterSet> countSets = new ArrayList<AggregateCounterSet>();
    for (CounterRow<String, Long> r : rows.get()) {
      expected_time = start;
      List<AggregateCounter> counters = new ArrayList<AggregateCounter>();
      for (HCounterColumn<Long> column : r.getColumnSlice().getColumns()) {
        AggregateCounter count = new AggregateCounter(column.getName(), column.getValue());
        if (pad && (resolution != CounterResolution.ALL)) {
          while (count.getTimestamp() != expected_time) {
            counters.add(new AggregateCounter(expected_time, 0));
            expected_time = resolution.next(expected_time);
          }
          expected_time = resolution.next(expected_time);
        }
        counters.add(count);
      }
      if (pad && (resolution != CounterResolution.ALL)) {
        while (expected_time <= finish) {
          counters.add(new AggregateCounter(expected_time, 0));
          expected_time = resolution.next(expected_time);
        }
      }
      AggregateCounterSelection selection = selections.get(r.getKey());
      countSets.add(new AggregateCounterSet(selection.getName(), queueId, selection.getCategory(), counters));
    }

    Collections.sort(countSets, new Comparator<AggregateCounterSet>() {
      @Override
      public int compare(AggregateCounterSet o1, AggregateCounterSet o2) {
        String s1 = o1.getName();
        String s2 = o2.getName();
        return s1.compareTo(s2);
      }
    });
    return countSets;
  }

  @Override
  public Results getAggregateQueueCounters(String queuePath, String category, String counterName,
      CounterResolution resolution, long start, long finish, boolean pad) {
    return Results.fromCounters(getAggregateCounters(getQueueId(queuePath), category, counterName, resolution, start,
        finish, pad));
  }

  @Override
  public Results getAggregateQueueCounters(String queuePath, CounterQuery query) throws Exception {
    return Results.fromCounters(getAggregateCounters(getQueueId(queuePath), query));
  }

  @Override
  public void incrementQueueCounters(String queuePath, Map<String, Long> counts) {
    long timestamp = cass.createTimestamp();
    Mutator<ByteBuffer> m = createMutator(cass.getApplicationKeyspace(applicationId), be);
    counterUtils.batchIncrementQueueCounters(m, getQueueId(queuePath), counts, timestamp, applicationId);
    batchExecute(m, CassandraService.RETRY_COUNT);
  }

  @Override
  public void incrementQueueCounter(String queuePath, String name, long value) {
    long timestamp = cass.createTimestamp();
    Mutator<ByteBuffer> m = createMutator(cass.getApplicationKeyspace(applicationId), be);
    counterUtils.batchIncrementQueueCounter(m, getQueueId(queuePath), name, value, timestamp, applicationId);
    batchExecute(m, CassandraService.RETRY_COUNT);
  }

  public Map<String, Long> getQueueCounters(UUID queueId) throws Exception {

    Map<String, Long> counters = new HashMap<String, Long>();
    Keyspace ko = cass.getApplicationKeyspace(applicationId);
    SliceCounterQuery<UUID, String> q = createCounterSliceQuery(ko, ue, se);
    q.setColumnFamily(COUNTERS.getColumnFamily());
    q.setRange(null, null, false, ALL_COUNT);
    QueryResult<CounterSlice<String>> r = q.setKey(queueId).execute();
    for (HCounterColumn<String> column : r.get().getColumns()) {
      counters.put(column.getName(), column.getValue());
    }
    return counters;
  }

  @Override
  public Map<String, Long> getQueueCounters(String queuePath) throws Exception {
    return getQueueCounters(getQueueId(queuePath));
  }

  @Override
  public Set<String> getQueueCounterNames(String queuePath) throws Exception {
    Set<String> names = new HashSet<String>();
    Keyspace ko = cass.getApplicationKeyspace(applicationId);
    SliceQuery<String, String, ByteBuffer> q = createSliceQuery(ko, se, se, be);
    q.setColumnFamily(QueuesCF.QUEUE_DICTIONARIES.toString());
    q.setKey(CassandraPersistenceUtils.key(getQueueId(queuePath), DICTIONARY_COUNTERS).toString());
    q.setRange(null, null, false, ALL_COUNT);

    List<HColumn<String, ByteBuffer>> columns = q.execute().get().getColumns();
    for (HColumn<String, ByteBuffer> column : columns) {
      names.add(column.getName());
    }
    return names;
  }

  public Queue getQueue(String queuePath, UUID queueId) {
    SliceQuery<UUID, String, ByteBuffer> q = createSliceQuery(cass.getApplicationKeyspace(applicationId), ue, se, be);
    q.setColumnFamily(QUEUE_PROPERTIES.getColumnFamily());
    q.setKey(queueId);
    q.setRange(null, null, false, ALL_COUNT);
    QueryResult<ColumnSlice<String, ByteBuffer>> r = q.execute();
    ColumnSlice<String, ByteBuffer> slice = r.get();
    List<HColumn<String, ByteBuffer>> results = slice.getColumns();
    return deserializeQueue(results);
  }

  @Override
  public Queue getQueue(String queuePath) {
    return getQueue(queuePath, getQueueId(queuePath));
  }

  @Override
  public Queue updateQueue(String queuePath, Queue queue) {
    queue.setPath(queuePath);

    UUID timestampUuid = newTimeUUID();
    long timestamp = getTimestampInMicros(timestampUuid);

    Mutator<ByteBuffer> batch = createMutator(cass.getApplicationKeyspace(applicationId), be);

    addQueueToMutator(batch, queue, timestamp);

    try {
      batchUpdateQueuePropertiesIndexes(batch, queuePath, queue.getUuid(), queue.getProperties(), timestampUuid);
    } catch (Exception e) {
      logger.error("Unable to update queue", e);
    }

    batch.addInsertion(bytebuffer(queue.getUuid()), QUEUE_PROPERTIES.getColumnFamily(),
        createColumn(QUEUE_CREATED, timestamp / 1000, Long.MAX_VALUE - timestamp, se, le));

    batch.addInsertion(bytebuffer(queue.getUuid()), QUEUE_PROPERTIES.getColumnFamily(),
        createColumn(QUEUE_MODIFIED, timestamp / 1000, timestamp, se, le));

    batchExecute(batch, RETRY_COUNT);

    return queue;
  }

  @Override
  public Queue updateQueue(String queuePath, Map<String, Object> properties) {
    return updateQueue(queuePath, new Queue(properties));
  }

  public void batchUpdateQueuePropertiesIndexes(Mutator<ByteBuffer> batch, String subscriberQueuePath,
      UUID subscriberQueueId, Map<String, Object> properties, UUID timestampUuid) throws Exception {

    QueueSet subscriptions = getSubscriptions(subscriberQueuePath, null, ALL_COUNT);

    if (subscriptions != null) {

      for (Map.Entry<String, Object> property : properties.entrySet()) {

        if (!Queue.QUEUE_PROPERTIES.containsKey(property.getKey())) {

          QueueIndexUpdate indexUpdate = batchStartQueueIndexUpdate(batch, subscriberQueuePath, subscriberQueueId,
              property.getKey(), property.getValue(), timestampUuid);

          for (QueueInfo subscription : subscriptions.getQueues()) {
            batchUpdateQueueIndex(indexUpdate, subscription.getUuid());
          }
        }
      }
    }

  }

  public void batchUpdateQueuePropertiesIndexes(Mutator<ByteBuffer> batch, UUID publisherQueueId,
      String subscriberQueuePath, UUID subscriberQueueId, Map<String, Object> properties, UUID timestampUuid)
      throws Exception {

    for (Map.Entry<String, Object> property : properties.entrySet()) {

      if (!Queue.QUEUE_PROPERTIES.containsKey(property.getKey())) {

        QueueIndexUpdate indexUpdate = batchStartQueueIndexUpdate(batch, subscriberQueuePath, subscriberQueueId,
            property.getKey(), property.getValue(), timestampUuid);

        batchUpdateQueueIndex(indexUpdate, publisherQueueId);
      }
    }

  }

  public QueueIndexUpdate batchUpdateQueueIndex(QueueIndexUpdate indexUpdate, UUID subcriptionQueueId) throws Exception {

    logger.info("batchUpdateQueueIndex");

    Mutator<ByteBuffer> batch = indexUpdate.getBatch();

    // queue_id,prop_name
    Object index_key = key(subcriptionQueueId, indexUpdate.getEntryName());

    // subscription_queue_id,subscriber_queue_id,prop_name

    for (QueueIndexEntry entry : indexUpdate.getPrevEntries()) {

      if (entry.getValue() != null) {

        index_key = key(subcriptionQueueId, entry.getPath());

        batch.addDeletion(bytebuffer(index_key), PROPERTY_INDEX.getColumnFamily(), entry.getIndexComposite(), dce,
            indexUpdate.getTimestamp());

      } else {
        logger.error("Unexpected condition - deserialized property value is null");
      }
    }

    if (indexUpdate.getNewEntries().size() > 0) {

      for (QueueIndexEntry indexEntry : indexUpdate.getNewEntries()) {

        index_key = key(subcriptionQueueId, indexEntry.getPath());

        batch.addInsertion(bytebuffer(index_key), PROPERTY_INDEX.getColumnFamily(),
            createColumn(indexEntry.getIndexComposite(), ByteBuffer.allocate(0), indexUpdate.getTimestamp(), dce, be));

      }

    }

    for (String index : indexUpdate.getIndexesSet()) {
      batch.addInsertion(bytebuffer(key(subcriptionQueueId, DICTIONARY_SUBSCRIBER_INDEXES)),
          QUEUE_DICTIONARIES.getColumnFamily(),
          createColumn(index, ByteBuffer.allocate(0), indexUpdate.getTimestamp(), se, be));
    }

    return indexUpdate;
  }

  public QueueIndexUpdate batchStartQueueIndexUpdate(Mutator<ByteBuffer> batch, String queuePath, UUID queueId,
      String entryName, Object entryValue, UUID timestampUuid) throws Exception {

    long timestamp = getTimestampInMicros(timestampUuid);

    QueueIndexUpdate indexUpdate = new QueueIndexUpdate(batch, queuePath, queueId, entryName, entryValue, timestampUuid);

    List<HColumn<ByteBuffer, ByteBuffer>> entries = null;

    entries = createSliceQuery(cass.getApplicationKeyspace(applicationId), ue, be, be)
        .setColumnFamily(PROPERTY_INDEX_ENTRIES.getColumnFamily())
        .setKey(queueId)
        .setRange(DynamicComposite.toByteBuffer(entryName),
            setGreaterThanEqualityFlag(new DynamicComposite(entryName)).serialize(), false, INDEX_ENTRY_LIST_COUNT)
        .execute().get().getColumns();

    if (logger.isInfoEnabled()) {
      logger.info("Found {} previous index entries for {} of entity {}", new Object[] { entries.size(), entryName,
          queueId });
    }

    // Delete all matching entries from entry list
    for (HColumn<ByteBuffer, ByteBuffer> entry : entries) {
      UUID prev_timestamp = null;
      Object prev_value = null;
      String prev_obj_path = null;

      // new format:
      // composite(entryName,
      // value_code,prev_value,prev_timestamp,prev_obj_path) = null
      DynamicComposite composite = DynamicComposite.fromByteBuffer(entry.getName().duplicate());
      prev_value = composite.get(2);
      prev_timestamp = (UUID) composite.get(3);
      if (composite.size() > 4) {
        prev_obj_path = (String) composite.get(4);
      }

      if (prev_value != null) {

        String entryPath = entryName;
        if ((prev_obj_path != null) && (prev_obj_path.length() > 0)) {
          entryPath = entryName + "." + prev_obj_path;
        }

        indexUpdate.addPrevEntry(entryPath, prev_value, prev_timestamp);

        // composite(property_value,subscriber_id,entry_timestamp)
        batch.addDeletion(bytebuffer(queueId), PROPERTY_INDEX_ENTRIES.getColumnFamily(), entry.getName().duplicate(),
            be, timestamp);

      } else {
        logger.error("Unexpected condition - deserialized property value is null");
      }
    }

    if (validIndexableValueOrJson(entryValue)) {

      List<Map.Entry<String, Object>> list = getKeyValueList(entryName, entryValue, false);

      for (Map.Entry<String, Object> indexEntry : list) {

        if (validIndexableValue(indexEntry.getValue())) {
          indexUpdate.addNewEntry(indexEntry.getKey(), toIndexableValue(indexEntry.getValue()));
        }

      }

      for (Map.Entry<String, Object> indexEntry : list) {

        String name = indexEntry.getKey();
        if (name.startsWith(entryName + ".")) {
          name = name.substring(entryName.length() + 1);
        } else if (name.startsWith(entryName)) {
          name = name.substring(entryName.length());
        }

        batch.addInsertion(
            bytebuffer(queueId),
            PROPERTY_INDEX_ENTRIES.getColumnFamily(),
            createColumn(DynamicComposite.toByteBuffer(entryName, indexValueCode(entryValue),
                toIndexableValue(entryValue), indexUpdate.getTimestampUuid(), name), ByteBuffer.allocate(0), timestamp,
                be, be));

        indexUpdate.addIndex(indexEntry.getKey());
      }

      indexUpdate.addIndex(entryName);

    }

    return indexUpdate;
  }

  public QueueSet searchQueueIndex(UUID publisherQueueId, QuerySlice slice, int count) throws Exception {

    ByteBuffer start = null;
    if (slice.getCursor() != null) {
      start = slice.getCursor();
    } else if (slice.getStart() != null) {
      DynamicComposite s = new DynamicComposite(slice.getStart().getCode(), slice.getStart().getValue());
      if (!slice.getStart().isInclusive()) {
        setEqualityFlag(s, ComponentEquality.GREATER_THAN_EQUAL);
      }
      start = s.serialize();
    }

    ByteBuffer finish = null;
    if (slice.getFinish() != null) {
      DynamicComposite f = new DynamicComposite(slice.getFinish().getCode(), slice.getFinish().getValue());
      if (slice.getFinish().isInclusive()) {
        setEqualityFlag(f, ComponentEquality.GREATER_THAN_EQUAL);
      }
      finish = f.serialize();
    }

    if (slice.isReversed() && (start != null) && (finish != null)) {
      ByteBuffer temp = start;
      start = finish;
      finish = temp;
    }

    List<HColumn<ByteBuffer, ByteBuffer>> results = createSliceQuery(cass.getApplicationKeyspace(applicationId), be,
        be, be).setColumnFamily(PROPERTY_INDEX.getColumnFamily())
        .setKey(bytebuffer(key(publisherQueueId, slice.getPropertyName())))
        .setRange(start, finish, slice.isReversed(), count).execute().get().getColumns();

    QueueSet queues = new QueueSet();
    for (HColumn<ByteBuffer, ByteBuffer> column : results) {
      DynamicComposite c = DynamicComposite.fromByteBuffer(column.getName());
      queues.addQueue(c.get(3, se), c.get(2, ue));
    }
    return queues;

  }

  @Override
  public QueueSet searchSubscribers(String publisherQueuePath, Query query) {

    if (query == null) {
      query = new Query();
    }

    publisherQueuePath = normalizeQueuePath(publisherQueuePath);
    UUID publisherQueueId = getQueueId(publisherQueuePath);

    if (!query.hasFilterPredicates() && !query.hasSortPredicates()) {

      return getSubscribers(publisherQueuePath, null, query.getLimit());

    }

    QueueSet results = null;
    String composite_cursor = null;

    QueryProcessor qp = new QueryProcessor(query);
    List<QuerySlice> slices = qp.getSlices();
    int search_count = query.getLimit() + 1;
    if (slices.size() > 1) {
      search_count = DEFAULT_SEARCH_COUNT;
    }
    for (QuerySlice slice : slices) {

      QueueSet r = null;
      try {
        r = searchQueueIndex(publisherQueueId, slice, search_count);
      } catch (Exception e) {
        logger.error("Error during search", e);
      }

      if (r == null) {
        continue;
      }

      if (r.size() > query.getLimit()) {
        r.setCursorToLastResult();
      }

      if (r.getCursor() != null) {
        if (composite_cursor != null) {
          composite_cursor += "|";
        } else {
          composite_cursor = "";
        }
        int hashCode = slice.hashCode();
        logger.info("Cursor hash code: {} ", hashCode);
        composite_cursor += hashCode + ":" + r.getCursor();
      }

      if (results != null) {
        results.and(r);
      } else {
        results = r;
      }

    }

    return results;
  }

  @Override
  public QueueSet getQueues(String firstQueuePath, int limit) {
    return getSubscribers("/", firstQueuePath, limit);
  }

  @Override
  public QueueSet getChildQueues(String publisherQueuePath, String firstQueuePath, int count) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public UUID getNewConsumerId() {
    // TODO Auto-generated method stub
    return null;
  }

}