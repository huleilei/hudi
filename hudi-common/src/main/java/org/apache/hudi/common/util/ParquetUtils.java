/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.common.util;

import org.apache.hudi.avro.HoodieAvroUtils;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.HoodieColumnRangeMetadata;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.exception.MetadataNotFoundException;
import org.apache.hudi.keygen.BaseKeyGenerator;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroReadSupport;
import org.apache.parquet.avro.AvroSchemaConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.OriginalType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Utility functions involving with parquet.
 */
public class ParquetUtils extends BaseFileUtils {

  private static Object lock = new Object();

  /**
   * Read the rowKey list matching the given filter, from the given parquet file. If the filter is empty, then this will
   * return all the rowkeys.
   *
   * @param filePath      The parquet file path.
   * @param configuration configuration to build fs object
   * @param filter        record keys filter
   * @return Set Set of row keys matching candidateRecordKeys
   */
  @Override
  public Set<String> filterRowKeys(Configuration configuration, Path filePath, Set<String> filter) {
    return filterParquetRowKeys(configuration, filePath, filter, HoodieAvroUtils.getRecordKeySchema());
  }

  /**
   * Read the rowKey list matching the given filter, from the given parquet file. If the filter is empty, then this will
   * return all the rowkeys.
   *
   * @param filePath      The parquet file path.
   * @param configuration configuration to build fs object
   * @param filter        record keys filter
   * @param readSchema    schema of columns to be read
   * @return Set Set of row keys matching candidateRecordKeys
   */
  private static Set<String> filterParquetRowKeys(Configuration configuration, Path filePath, Set<String> filter,
                                                  Schema readSchema) {
    Option<RecordKeysFilterFunction> filterFunction = Option.empty();
    if (filter != null && !filter.isEmpty()) {
      filterFunction = Option.of(new RecordKeysFilterFunction(filter));
    }
    Configuration conf = new Configuration(configuration);
    conf.addResource(FSUtils.getFs(filePath.toString(), conf).getConf());
    AvroReadSupport.setAvroReadSchema(conf, readSchema);
    AvroReadSupport.setRequestedProjection(conf, readSchema);
    Set<String> rowKeys = new HashSet<>();
    try (ParquetReader reader = AvroParquetReader.builder(filePath).withConf(conf).build()) {
      Object obj = reader.read();
      while (obj != null) {
        if (obj instanceof GenericRecord) {
          String recordKey = ((GenericRecord) obj).get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString();
          if (!filterFunction.isPresent() || filterFunction.get().apply(recordKey)) {
            rowKeys.add(recordKey);
          }
        }
        obj = reader.read();
      }
    } catch (IOException e) {
      throw new HoodieIOException("Failed to read row keys from Parquet " + filePath, e);

    }
    // ignore
    return rowKeys;
  }

  /**
   * Fetch {@link HoodieKey}s from the given parquet file.
   *
   * @param filePath      The parquet file path.
   * @param configuration configuration to build fs object
   * @return {@link List} of {@link HoodieKey}s fetched from the parquet file
   */
  @Override
  public List<HoodieKey> fetchRecordKeyPartitionPath(Configuration configuration, Path filePath) {
    return fetchRecordKeyPartitionPathInternal(configuration, filePath, Option.empty());
  }

  private List<HoodieKey> fetchRecordKeyPartitionPathInternal(Configuration configuration, Path filePath, Option<BaseKeyGenerator> keyGeneratorOpt) {
    List<HoodieKey> hoodieKeys = new ArrayList<>();
    try {
      Configuration conf = new Configuration(configuration);
      conf.addResource(FSUtils.getFs(filePath.toString(), conf).getConf());
      Schema readSchema = keyGeneratorOpt.map(keyGenerator -> {
        List<String> fields = new ArrayList<>();
        fields.addAll(keyGenerator.getRecordKeyFields());
        fields.addAll(keyGenerator.getPartitionPathFields());
        return HoodieAvroUtils.getSchemaForFields(readAvroSchema(conf, filePath), fields);
      })
          .orElse(HoodieAvroUtils.getRecordKeyPartitionPathSchema());
      AvroReadSupport.setAvroReadSchema(conf, readSchema);
      AvroReadSupport.setRequestedProjection(conf, readSchema);
      ParquetReader reader = AvroParquetReader.builder(filePath).withConf(conf).build();
      Object obj = reader.read();
      while (obj != null) {
        if (obj instanceof GenericRecord) {
          String recordKey = null;
          String partitionPath = null;
          if (keyGeneratorOpt.isPresent()) {
            recordKey = keyGeneratorOpt.get().getRecordKey((GenericRecord) obj);
            partitionPath = keyGeneratorOpt.get().getPartitionPath((GenericRecord) obj);
          } else {
            recordKey = ((GenericRecord) obj).get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString();
            partitionPath = ((GenericRecord) obj).get(HoodieRecord.PARTITION_PATH_METADATA_FIELD).toString();
          }
          hoodieKeys.add(new HoodieKey(recordKey, partitionPath));
          obj = reader.read();
        }
      }
    } catch (IOException e) {
      throw new HoodieIOException("Failed to read from Parquet file " + filePath, e);
    }
    return hoodieKeys;
  }

  /**
   * Fetch {@link HoodieKey}s from the given parquet file.
   *
   * @param configuration   configuration to build fs object
   * @param filePath        The parquet file path.
   * @param keyGeneratorOpt
   * @return {@link List} of {@link HoodieKey}s fetched from the parquet file
   */
  @Override
  public List<HoodieKey> fetchRecordKeyPartitionPath(Configuration configuration, Path filePath, Option<BaseKeyGenerator> keyGeneratorOpt) {
    return fetchRecordKeyPartitionPathInternal(configuration, filePath, keyGeneratorOpt);
  }

  public ParquetMetadata readMetadata(Configuration conf, Path parquetFilePath) {
    ParquetMetadata footer;
    try {
      // TODO(vc): Should we use the parallel reading version here?
      footer = ParquetFileReader.readFooter(FSUtils.getFs(parquetFilePath.toString(), conf).getConf(), parquetFilePath);
    } catch (IOException e) {
      throw new HoodieIOException("Failed to read footer for parquet " + parquetFilePath, e);
    }
    return footer;
  }

  /**
   * Get the schema of the given parquet file.
   */
  public MessageType readSchema(Configuration configuration, Path parquetFilePath) {
    return readMetadata(configuration, parquetFilePath).getFileMetaData().getSchema();
  }

  @Override
  public Map<String, String> readFooter(Configuration configuration, boolean required,
                                                       Path parquetFilePath, String... footerNames) {
    Map<String, String> footerVals = new HashMap<>();
    ParquetMetadata footer = readMetadata(configuration, parquetFilePath);
    Map<String, String> metadata = footer.getFileMetaData().getKeyValueMetaData();
    for (String footerName : footerNames) {
      if (metadata.containsKey(footerName)) {
        footerVals.put(footerName, metadata.get(footerName));
      } else if (required) {
        throw new MetadataNotFoundException(
            "Could not find index in Parquet footer. Looked for key " + footerName + " in " + parquetFilePath);
      }
    }
    return footerVals;
  }

  @Override
  public Schema readAvroSchema(Configuration configuration, Path parquetFilePath) {
    MessageType parquetSchema = readSchema(configuration, parquetFilePath);
    return new AvroSchemaConverter(configuration).convert(parquetSchema);
  }

  /**
   * NOTE: This literally reads the entire file contents, thus should be used with caution.
   */
  @Override
  public List<GenericRecord> readAvroRecords(Configuration configuration, Path filePath) {
    ParquetReader reader = null;
    List<GenericRecord> records = new ArrayList<>();
    try {
      reader = AvroParquetReader.builder(filePath).withConf(configuration).build();
      Object obj = reader.read();
      while (obj != null) {
        if (obj instanceof GenericRecord) {
          records.add(((GenericRecord) obj));
        }
        obj = reader.read();
      }
    } catch (IOException e) {
      throw new HoodieIOException("Failed to read avro records from Parquet " + filePath, e);

    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (IOException e) {
          // ignore
        }
      }
    }
    return records;
  }

  @Override
  public List<GenericRecord> readAvroRecords(Configuration configuration, Path filePath, Schema schema) {
    AvroReadSupport.setAvroReadSchema(configuration, schema);
    return readAvroRecords(configuration, filePath);
  }

  /**
   * Returns the number of records in the parquet file.
   *
   * @param conf            Configuration
   * @param parquetFilePath path of the file
   */
  @Override
  public long getRowCount(Configuration conf, Path parquetFilePath) {
    ParquetMetadata footer;
    long rowCount = 0;
    footer = readMetadata(conf, parquetFilePath);
    for (BlockMetaData b : footer.getBlocks()) {
      rowCount += b.getRowCount();
    }
    return rowCount;
  }

  static class RecordKeysFilterFunction implements Function<String, Boolean> {

    private final Set<String> candidateKeys;

    RecordKeysFilterFunction(Set<String> candidateKeys) {
      this.candidateKeys = candidateKeys;
    }

    @Override
    public Boolean apply(String recordKey) {
      return candidateKeys.contains(recordKey);
    }
  }

  /**
   * Parse min/max statistics stored in parquet footers for all columns.
   * ParquetRead.readFooter is not a thread safe method.
   *
   * @param conf hadoop conf.
   * @param parquetFilePath file to be read.
   * @param cols cols which need to collect statistics.
   * @return a HoodieColumnRangeMetadata instance.
   */
  public Collection<HoodieColumnRangeMetadata<Comparable>> readRangeFromParquetMetadata(
      Configuration conf,
      Path parquetFilePath,
      List<String> cols) {
    ParquetMetadata metadata = readMetadata(conf, parquetFilePath);
    // collect stats from all parquet blocks
    Map<String, List<HoodieColumnRangeMetadata<Comparable>>> columnToStatsListMap = metadata.getBlocks().stream().flatMap(blockMetaData -> {
      return blockMetaData.getColumns().stream().filter(f -> cols.contains(f.getPath().toDotString())).map(columnChunkMetaData -> {
        String minAsString;
        String maxAsString;
        if (columnChunkMetaData.getPrimitiveType().getOriginalType() == OriginalType.DATE) {
          synchronized (lock) {
            minAsString = columnChunkMetaData.getStatistics().minAsString();
            maxAsString = columnChunkMetaData.getStatistics().maxAsString();
          }
        } else {
          minAsString = columnChunkMetaData.getStatistics().minAsString();
          maxAsString = columnChunkMetaData.getStatistics().maxAsString();
        }
        return new HoodieColumnRangeMetadata<>(parquetFilePath.getName(), columnChunkMetaData.getPath().toDotString(),
            columnChunkMetaData.getStatistics().genericGetMin(),
            columnChunkMetaData.getStatistics().genericGetMax(),
            columnChunkMetaData.getStatistics().getNumNulls(),
            minAsString, maxAsString);
      });
    }).collect(Collectors.groupingBy(e -> e.getColumnName()));

    // we only intend to keep file level statistics.
    return new ArrayList<>(columnToStatsListMap.values().stream()
        .map(blocks -> getColumnRangeInFile(blocks))
        .collect(Collectors.toList()));
  }

  private HoodieColumnRangeMetadata<Comparable> getColumnRangeInFile(final List<HoodieColumnRangeMetadata<Comparable>> blockRanges) {
    if (blockRanges.size() == 1) {
      // only one block in parquet file. we can just return that range.
      return blockRanges.get(0);
    } else {
      // there are multiple blocks. Compute min(block_mins) and max(block_maxs)
      return blockRanges.stream().reduce((b1, b2) -> combineRanges(b1, b2)).get();
    }
  }

  private HoodieColumnRangeMetadata<Comparable> combineRanges(HoodieColumnRangeMetadata<Comparable> range1,
                                                  HoodieColumnRangeMetadata<Comparable> range2) {
    final Comparable minValue;
    final Comparable maxValue;
    final String minValueAsString;
    final String maxValueAsString;
    if (range1.getMinValue() != null && range2.getMinValue() != null) {
      if (range1.getMinValue().compareTo(range2.getMinValue()) < 0) {
        minValue = range1.getMinValue();
        minValueAsString = range1.getMinValueAsString();
      } else {
        minValue = range2.getMinValue();
        minValueAsString = range2.getMinValueAsString();
      }
    } else if (range1.getMinValue() == null) {
      minValue = range2.getMinValue();
      minValueAsString = range2.getMinValueAsString();
    } else {
      minValue = range1.getMinValue();
      minValueAsString = range1.getMinValueAsString();
    }

    if (range1.getMaxValue() != null && range2.getMaxValue() != null) {
      if (range1.getMaxValue().compareTo(range2.getMaxValue()) < 0) {
        maxValue = range2.getMaxValue();
        maxValueAsString = range2.getMaxValueAsString();
      } else {
        maxValue = range1.getMaxValue();
        maxValueAsString = range1.getMaxValueAsString();
      }
    } else if (range1.getMaxValue() == null) {
      maxValue = range2.getMaxValue();
      maxValueAsString = range2.getMaxValueAsString();
    } else  {
      maxValue = range1.getMaxValue();
      maxValueAsString = range1.getMaxValueAsString();
    }

    return new HoodieColumnRangeMetadata<>(range1.getFilePath(),
        range1.getColumnName(), minValue, maxValue, range1.getNumNulls() + range2.getNumNulls(), minValueAsString, maxValueAsString);
  }
}
