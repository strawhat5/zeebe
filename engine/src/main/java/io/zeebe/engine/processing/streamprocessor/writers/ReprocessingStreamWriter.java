/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.processing.streamprocessor.writers;

import io.zeebe.engine.processing.streamprocessor.TypedRecord;
import io.zeebe.msgpack.UnpackedObject;
import io.zeebe.protocol.impl.record.RecordMetadata;
import io.zeebe.protocol.record.RejectionType;
import io.zeebe.protocol.record.intent.Intent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ReprocessingStreamWriter implements TypedStreamWriter {

  public final List<ReprocessingRecord> reprocessingRecords = new ArrayList<>();

  private long sourceRecordPosition = -1L;

  @Override
  public void appendRejection(
      final TypedRecord<? extends UnpackedObject> command,
      final RejectionType type,
      final String reason) {
    // no op implementation
  }

  @Override
  public void appendRejection(
      final TypedRecord<? extends UnpackedObject> command,
      final RejectionType type,
      final String reason,
      final Consumer<RecordMetadata> metadata) {
    // no op implementation
  }

  @Override
  public void appendNewEvent(final long key, final Intent intent, final UnpackedObject value) {
    final var record = new ReprocessingRecord();
    record.key = key;
    record.intent = intent;
    record.sourceRecordPosition = sourceRecordPosition;

    reprocessingRecords.add(record);
  }

  @Override
  public void appendFollowUpEvent(final long key, final Intent intent, final UnpackedObject value) {
    final var record = new ReprocessingRecord();
    record.key = key;
    record.intent = intent;
    record.sourceRecordPosition = sourceRecordPosition;

    reprocessingRecords.add(record);
  }

  @Override
  public void appendFollowUpEvent(
      final long key,
      final Intent intent,
      final UnpackedObject value,
      final Consumer<RecordMetadata> metadata) {
    final var record = new ReprocessingRecord();
    record.key = key;
    record.intent = intent;
    record.sourceRecordPosition = sourceRecordPosition;
    reprocessingRecords.add(record);
  }

  @Override
  public void configureSourceContext(final long sourceRecordPosition) {
    this.sourceRecordPosition = sourceRecordPosition;
  }

  @Override
  public void appendNewCommand(final Intent intent, final UnpackedObject value) {
    final var record = new ReprocessingRecord();
    record.key = -1L;
    record.intent = intent;
    record.sourceRecordPosition = sourceRecordPosition;

    reprocessingRecords.add(record);
  }

  @Override
  public void appendFollowUpCommand(
      final long key, final Intent intent, final UnpackedObject value) {
    final var record = new ReprocessingRecord();
    record.key = key;
    record.intent = intent;
    record.sourceRecordPosition = sourceRecordPosition;

    reprocessingRecords.add(record);
  }

  @Override
  public void appendFollowUpCommand(
      final long key,
      final Intent intent,
      final UnpackedObject value,
      final Consumer<RecordMetadata> metadata) {
    final var record = new ReprocessingRecord();
    record.key = key;
    record.intent = intent;
    record.sourceRecordPosition = sourceRecordPosition;

    reprocessingRecords.add(record);
  }

  @Override
  public void reset() {
    // no op implementation
    sourceRecordPosition = -1;
  }

  @Override
  public long flush() {
    return 0;
  }

  public static class ReprocessingRecord {

    private long key;
    private long sourceRecordPosition;
    private Intent intent;

    public long getKey() {
      return key;
    }

    public long getSourceRecordPosition() {
      return sourceRecordPosition;
    }

    public Intent getIntent() {
      return intent;
    }

    @Override
    public String toString() {
      return "{"
          + "key="
          + key
          + ", sourceRecordPosition="
          + sourceRecordPosition
          + ", intent="
          + intent
          + '}';
    }
  }
}
