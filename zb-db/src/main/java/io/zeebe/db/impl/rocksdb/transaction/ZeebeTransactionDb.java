/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.db.impl.rocksdb.transaction;

import static io.zeebe.util.buffer.BufferUtil.startsWith;

import io.prometheus.client.Gauge;
import io.zeebe.db.ColumnFamily;
import io.zeebe.db.DbContext;
import io.zeebe.db.DbKey;
import io.zeebe.db.DbValue;
import io.zeebe.db.KeyValuePairVisitor;
import io.zeebe.db.ZeebeDb;
import io.zeebe.db.ZeebeDbException;
import io.zeebe.db.impl.rocksdb.Loggers;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.agrona.DirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.rocksdb.Checkpoint;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.OptimisticTransactionDB;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.RocksObject;
import org.rocksdb.Transaction;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;

public class ZeebeTransactionDb<ColumnFamilyNames extends Enum<ColumnFamilyNames>>
    implements ZeebeDb<ColumnFamilyNames> {

  private static final Logger LOG = Loggers.DB_LOGGER;
  private static final String ERROR_MESSAGE_CLOSE_RESOURCE =
      "Expected to close RocksDB resource successfully, but exception was thrown. Will continue to close remaining resources.";

  private static final String PARTITION = "partition";
  private static final String COLUMN_FAMILY_NAME = "columnFamilyName";
  private static final String PROPERTY_NAME = "propertyName";
  private static final String ZEEBE_NAMESPACE = "zeebe";

  private static final Gauge MEMORY_PROPERTIES =
      Gauge.build()
          .namespace(ZEEBE_NAMESPACE)
          .name("rocksdb_memory")
          .help(
              "Everything which might be related to current memory consumption of RocksDB per column family and partition")
          .labelNames(PARTITION, COLUMN_FAMILY_NAME, PROPERTY_NAME)
          .register();

  private static final String[] MEMORY_PROPERTIES_STRINGS = {
    "rocksdb.cur-size-all-mem-tables",
    "rocksdb.cur-size-active-mem-table",
    "rocksdb.size-all-mem-tables",
    "rocksdb.block-cache-usage",
    "rocksdb.block-cache-capacity",
    "rocksdb.block-cache-pinned-usage",
    "rocksdb.estimate-table-readers-mem",
  };

  private static final Gauge SST_PROPERTIES =
      Gauge.build()
          .namespace(ZEEBE_NAMESPACE)
          .name("rocksdb_sst")
          .help(
              "Everything which is related to SST files in RocksDB per column family and partition")
          .labelNames(PARTITION, COLUMN_FAMILY_NAME, PROPERTY_NAME)
          .register();

  private static final String[] SST_PROPERTIES_STRINGS = {
    "rocksdb.total-sst-files-size", "rocksdb.live-sst-files-size",
  };

  private static final Gauge LIVE_PROPERTIES =
      Gauge.build()
          .namespace(ZEEBE_NAMESPACE)
          .name("rocksdb_live")
          .help(
              "Other estimated properties based on entries in RocksDb per column family and partition")
          .labelNames(PARTITION, COLUMN_FAMILY_NAME, PROPERTY_NAME)
          .register();

  private static final String[] LIVE_PROPERTIES_STRINGS = {
    "rocksdb.num-entries-imm-mem-tables",
    "rocksdb.estimate-num-keys",
    "rocksdb.estimate-live-data-size"
  };

  private static final Gauge WRITE_PROPERTIES =
      Gauge.build()
          .namespace(ZEEBE_NAMESPACE)
          .name("rocksdb_writes")
          .help(
              "Properties related to writes, flushes and compactions for RocksDb per column family and partition")
          .labelNames(PARTITION, COLUMN_FAMILY_NAME, PROPERTY_NAME)
          .register();

  private static final String[] WRITE_PROPERTIES_STRINGS = {
    "rocksdb.is-write-stopped",
    "rocksdb.actual-delayed-write-rate",
    "rocksdb.mem-table-flush-pending",
    "rocksdb.num-running-flushes",
    "rocksdb.num-running-compactions"
  };

  private final OptimisticTransactionDB optimisticTransactionDB;
  private final List<AutoCloseable> closables;
  private final EnumMap<ColumnFamilyNames, Long> columnFamilyMap;
  private final Long2ObjectHashMap<ColumnFamilyHandle> handelToEnumMap;
  private final ReadOptions prefixReadOptions;
  private final ReadOptions defaultReadOptions;
  private final WriteOptions defaultWriteOptions;

  protected ZeebeTransactionDb(
      final OptimisticTransactionDB optimisticTransactionDB,
      final EnumMap<ColumnFamilyNames, Long> columnFamilyMap,
      final Long2ObjectHashMap<ColumnFamilyHandle> handelToEnumMap,
      final List<AutoCloseable> closables) {
    this.optimisticTransactionDB = optimisticTransactionDB;
    this.columnFamilyMap = columnFamilyMap;
    this.handelToEnumMap = handelToEnumMap;
    this.closables = closables;

    prefixReadOptions = new ReadOptions().setPrefixSameAsStart(true).setTotalOrderSeek(false);
    closables.add(prefixReadOptions);
    defaultReadOptions = new ReadOptions();
    closables.add(defaultReadOptions);
    defaultWriteOptions = new WriteOptions();
    closables.add(defaultWriteOptions);
  }

  public static <ColumnFamilyNames extends Enum<ColumnFamilyNames>>
      ZeebeTransactionDb<ColumnFamilyNames> openTransactionalDb(
          final DBOptions options,
          final String path,
          final List<ColumnFamilyDescriptor> columnFamilyDescriptors,
          final List<AutoCloseable> closables,
          final Class<ColumnFamilyNames> columnFamilyTypeClass)
          throws RocksDBException {
    final EnumMap<ColumnFamilyNames, Long> columnFamilyMap = new EnumMap<>(columnFamilyTypeClass);

    final List<ColumnFamilyHandle> handles = new ArrayList<>();
    final OptimisticTransactionDB optimisticTransactionDB =
        OptimisticTransactionDB.open(options, path, columnFamilyDescriptors, handles);
    closables.add(optimisticTransactionDB);

    final ColumnFamilyNames[] enumConstants = columnFamilyTypeClass.getEnumConstants();
    final Long2ObjectHashMap<ColumnFamilyHandle> handleToEnumMap = new Long2ObjectHashMap<>();
    for (int i = 0; i < handles.size(); i++) {
      final ColumnFamilyHandle columnFamilyHandle = handles.get(i);
      closables.add(columnFamilyHandle);
      columnFamilyMap.put(enumConstants[i], getNativeHandle(columnFamilyHandle));
      handleToEnumMap.put(getNativeHandle(handles.get(i)), handles.get(i));
    }

    return new ZeebeTransactionDb<>(
        optimisticTransactionDB, columnFamilyMap, handleToEnumMap, closables);
  }

  private static long getNativeHandle(final RocksObject object) {
    try {
      return RocksDbInternal.nativeHandle.getLong(object);
    } catch (final IllegalAccessException e) {
      throw new RuntimeException(
          "Unexpected error occurred trying to access private nativeHandle_ field", e);
    }
  }

  long getColumnFamilyHandle(final ColumnFamilyNames columnFamily) {
    return columnFamilyMap.get(columnFamily);
  }

  @Override
  public <KeyType extends DbKey, ValueType extends DbValue>
      ColumnFamily<KeyType, ValueType> createColumnFamily(
          final ColumnFamilyNames columnFamily,
          final DbContext context,
          final KeyType keyInstance,
          final ValueType valueInstance) {
    return new TransactionalColumnFamily<>(this, columnFamily, context, keyInstance, valueInstance);
  }

  @Override
  public void createSnapshot(final File snapshotDir) {
    try (final Checkpoint checkpoint = Checkpoint.create(optimisticTransactionDB)) {
      try {
        checkpoint.createCheckpoint(snapshotDir.getAbsolutePath());
      } catch (final RocksDBException rocksException) {
        throw new ZeebeDbException(rocksException);
      }
    }
  }

  @Override
  public DbContext createContext() {
    final Transaction transaction = optimisticTransactionDB.beginTransaction(defaultWriteOptions);
    final ZeebeTransaction zeebeTransaction = new ZeebeTransaction(transaction);
    closables.add(zeebeTransaction);
    return new DefaultDbContext(zeebeTransaction);
  }

  @Override
  public void dumpMetrics(final String name) {

    final var handles = handelToEnumMap.values();
    for (final ColumnFamilyHandle handle : handles) {
      exportMetrics(name, handle, MEMORY_PROPERTIES_STRINGS, MEMORY_PROPERTIES);
      exportMetrics(name, handle, LIVE_PROPERTIES_STRINGS, LIVE_PROPERTIES);
      exportMetrics(name, handle, SST_PROPERTIES_STRINGS, SST_PROPERTIES);
      exportMetrics(name, handle, WRITE_PROPERTIES_STRINGS, WRITE_PROPERTIES);
    }
  }

  private void exportMetrics(
      final String name,
      final ColumnFamilyHandle handle,
      final String[] propertyNames,
      final Gauge gaugeMetric) {
    for (final String propertyName : propertyNames) {
      gaugeMetric
          .labels(name, getColumnFamilyName(handle), propertyName)
          .set(Double.parseDouble(readProperty(handle, propertyName)));
    }
  }

  private String getColumnFamilyName(final ColumnFamilyHandle handle) {
    try {
      return new String(handle.getName());
    } catch (final RocksDBException e) {
      return null; // meh
    }
  }

  private String readProperty(final ColumnFamilyHandle handle, final String propertyName) {
    String propertyValue = null;
    try {
      propertyValue = optimisticTransactionDB.getProperty(handle, propertyName);
    } catch (final RocksDBException rde) {
      // it's fine
    }
    return propertyValue;
  }

  ////////////////////////////////////////////////////////////////////
  //////////////////////////// GET ///////////////////////////////////
  ////////////////////////////////////////////////////////////////////

  protected void put(
      final long columnFamilyHandle,
      final DbContext context,
      final DbKey key,
      final DbValue value) {
    ensureInOpenTransaction(
        context,
        transaction -> {
          context.writeKey(key);
          context.writeValue(value);

          transaction.put(
              columnFamilyHandle,
              context.getKeyBufferArray(),
              key.getLength(),
              context.getValueBufferArray(),
              value.getLength());
        });
  }

  private void ensureInOpenTransaction(
      final DbContext context, final TransactionConsumer operation) {
    context.runInTransaction(
        () -> operation.run((ZeebeTransaction) context.getCurrentTransaction()));
  }

  protected DirectBuffer get(
      final long columnFamilyHandle, final DbContext context, final DbKey key) {
    context.writeKey(key);
    final int keyLength = key.getLength();
    return getValue(columnFamilyHandle, context, keyLength);
  }

  private DirectBuffer getValue(
      final long columnFamilyHandle, final DbContext context, final int keyLength) {
    ensureInOpenTransaction(
        context,
        transaction -> {
          final byte[] value =
              transaction.get(
                  columnFamilyHandle,
                  getNativeHandle(defaultReadOptions),
                  context.getKeyBufferArray(),
                  keyLength);
          context.wrapValueView(value);
        });
    return context.getValueView();
  }

  ////////////////////////////////////////////////////////////////////
  //////////////////////////// ITERATION /////////////////////////////
  ////////////////////////////////////////////////////////////////////

  protected boolean exists(
      final long columnFamilyHandle, final DbContext context, final DbKey key) {
    context.wrapValueView(new byte[0]);
    ensureInOpenTransaction(
        context,
        transaction -> {
          context.writeKey(key);
          getValue(columnFamilyHandle, context, key.getLength());
        });
    return !context.isValueViewEmpty();
  }

  protected void delete(final long columnFamilyHandle, final DbContext context, final DbKey key) {
    context.writeKey(key);

    ensureInOpenTransaction(
        context,
        transaction ->
            transaction.delete(columnFamilyHandle, context.getKeyBufferArray(), key.getLength()));
  }

  ////////////////////////////////////////////////////////////////////
  //////////////////////////// ITERATION /////////////////////////////
  ////////////////////////////////////////////////////////////////////

  RocksIterator newIterator(
      final long columnFamilyHandle, final DbContext context, final ReadOptions options) {
    final ColumnFamilyHandle handle = handelToEnumMap.get(columnFamilyHandle);
    return context.newIterator(options, handle);
  }

  public <ValueType extends DbValue> void foreach(
      final long columnFamilyHandle,
      final DbContext context,
      final ValueType iteratorValue,
      final Consumer<ValueType> consumer) {
    foreach(
        columnFamilyHandle,
        context,
        (keyBuffer, valueBuffer) -> {
          iteratorValue.wrap(valueBuffer, 0, valueBuffer.capacity());
          consumer.accept(iteratorValue);
        });
  }

  public <KeyType extends DbKey, ValueType extends DbValue> void foreach(
      final long columnFamilyHandle,
      final DbContext context,
      final KeyType iteratorKey,
      final ValueType iteratorValue,
      final BiConsumer<KeyType, ValueType> consumer) {
    foreach(
        columnFamilyHandle,
        context,
        (keyBuffer, valueBuffer) -> {
          iteratorKey.wrap(keyBuffer, 0, keyBuffer.capacity());
          iteratorValue.wrap(valueBuffer, 0, valueBuffer.capacity());
          consumer.accept(iteratorKey, iteratorValue);
        });
  }

  private void foreach(
      final long columnFamilyHandle,
      final DbContext context,
      final BiConsumer<DirectBuffer, DirectBuffer> keyValuePairConsumer) {
    ensureInOpenTransaction(
        context,
        transaction -> {
          try (final RocksIterator iterator =
              newIterator(columnFamilyHandle, context, defaultReadOptions)) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
              context.wrapKeyView(iterator.key());
              context.wrapValueView(iterator.value());
              keyValuePairConsumer.accept(context.getKeyView(), context.getValueView());
            }
          }
        });
  }

  public <KeyType extends DbKey, ValueType extends DbValue> void whileTrue(
      final long columnFamilyHandle,
      final DbContext context,
      final KeyType keyInstance,
      final ValueType valueInstance,
      final KeyValuePairVisitor<KeyType, ValueType> visitor) {
    ensureInOpenTransaction(
        context,
        transaction -> {
          try (final RocksIterator iterator =
              newIterator(columnFamilyHandle, context, defaultReadOptions)) {
            boolean shouldVisitNext = true;
            for (iterator.seekToFirst(); iterator.isValid() && shouldVisitNext; iterator.next()) {
              shouldVisitNext = visit(context, keyInstance, valueInstance, visitor, iterator);
            }
          }
        });
  }

  protected <KeyType extends DbKey, ValueType extends DbValue> void whileEqualPrefix(
      final long columnFamilyHandle,
      final DbContext context,
      final DbKey prefix,
      final KeyType keyInstance,
      final ValueType valueInstance,
      final BiConsumer<KeyType, ValueType> visitor) {
    whileEqualPrefix(
        columnFamilyHandle,
        context,
        prefix,
        keyInstance,
        valueInstance,
        (k, v) -> {
          visitor.accept(k, v);
          return true;
        });
  }

  /**
   * NOTE: it doesn't seem possible in Java RocksDB to set a flexible prefix extractor on iterators
   * at the moment, so using prefixes seem to be mostly related to skipping files that do not
   * contain keys with the given prefix (which is useful anyway), but it will still iterate over all
   * keys contained in those files, so we still need to make sure the key actually matches the
   * prefix.
   *
   * <p>While iterating over subsequent keys we have to validate it.
   */
  protected <KeyType extends DbKey, ValueType extends DbValue> void whileEqualPrefix(
      final long columnFamilyHandle,
      final DbContext context,
      final DbKey prefix,
      final KeyType keyInstance,
      final ValueType valueInstance,
      final KeyValuePairVisitor<KeyType, ValueType> visitor) {
    context.withPrefixKeyBuffer(
        prefixKeyBuffer ->
            ensureInOpenTransaction(
                context,
                transaction -> {
                  try (final RocksIterator iterator =
                      newIterator(columnFamilyHandle, context, prefixReadOptions)) {
                    prefix.write(prefixKeyBuffer, 0);
                    final int prefixLength = prefix.getLength();

                    boolean shouldVisitNext = true;

                    for (RocksDbInternal.seek(
                            iterator,
                            getNativeHandle(iterator),
                            prefixKeyBuffer.byteArray(),
                            prefixLength);
                        iterator.isValid() && shouldVisitNext;
                        iterator.next()) {
                      final byte[] keyBytes = iterator.key();
                      if (!startsWith(
                          prefixKeyBuffer.byteArray(),
                          0,
                          prefix.getLength(),
                          keyBytes,
                          0,
                          keyBytes.length)) {
                        break;
                      }

                      shouldVisitNext =
                          visit(context, keyInstance, valueInstance, visitor, iterator);
                    }
                  }
                }));
  }

  private <KeyType extends DbKey, ValueType extends DbValue> boolean visit(
      final DbContext context,
      final KeyType keyInstance,
      final ValueType valueInstance,
      final KeyValuePairVisitor<KeyType, ValueType> iteratorConsumer,
      final RocksIterator iterator) {
    context.wrapKeyView(iterator.key());
    context.wrapValueView(iterator.value());

    final DirectBuffer keyViewBuffer = context.getKeyView();
    keyInstance.wrap(keyViewBuffer, 0, keyViewBuffer.capacity());
    final DirectBuffer valueViewBuffer = context.getValueView();
    valueInstance.wrap(valueViewBuffer, 0, valueViewBuffer.capacity());

    return iteratorConsumer.visit(keyInstance, valueInstance);
  }

  public boolean isEmpty(final long columnFamilyHandle, final DbContext context) {
    final AtomicBoolean isEmpty = new AtomicBoolean(false);
    ensureInOpenTransaction(
        context,
        transaction -> {
          try (final RocksIterator iterator =
              newIterator(columnFamilyHandle, context, defaultReadOptions)) {
            iterator.seekToFirst();
            final boolean hasEntry = iterator.isValid();
            isEmpty.set(!hasEntry);
          }
        });
    return isEmpty.get();
  }

  @Override
  public void close() {
    // Correct order of closing
    // 1. transaction
    // 2. options
    // 3. column family handles
    // 4. database
    // 5. db options
    // 6. column family options
    // https://github.com/facebook/rocksdb/wiki/RocksJava-Basics#opening-a-database-with-column-families
    Collections.reverse(closables);
    closables.forEach(
        closable -> {
          try {
            closable.close();
          } catch (final Exception e) {
            LOG.error(ERROR_MESSAGE_CLOSE_RESOURCE, e);
          }
        });
  }

  @FunctionalInterface
  interface TransactionConsumer {
    void run(ZeebeTransaction transaction) throws Exception;
  }
}
