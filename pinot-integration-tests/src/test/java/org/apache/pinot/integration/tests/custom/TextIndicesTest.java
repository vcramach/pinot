/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.integration.tests.custom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.avro.Schema.Field;
import org.apache.avro.Schema.Type;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.pinot.spi.config.table.FieldConfig;
import org.apache.pinot.spi.config.table.TableConfig;
import org.apache.pinot.spi.config.table.TableType;
import org.apache.pinot.spi.data.FieldSpec;
import org.apache.pinot.spi.data.Schema;
import org.apache.pinot.spi.utils.builder.TableConfigBuilder;
import org.apache.pinot.util.TestUtils;
import org.testng.annotations.Test;

import static org.apache.avro.Schema.create;
import static org.apache.avro.Schema.createUnion;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;


@Test(suiteName = "CustomClusterIntegrationTest")
public class TextIndicesTest extends CustomDataQueryClusterIntegrationTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String DEFAULT_TABLE_NAME = "TextIndicesTest";

  private static final String TEXT_COLUMN_NULL_NAME = "nullable_skills";
  private static final String TEXT_COLUMN_NAME = "skills";
  private static final String TEXT_COLUMN_NAME_CASE_SENSITIVE = "skills_case_sensitive";
  private static final String TEXT_COLUMN_NAME_NATIVE = "skills_native";
  private static final String TIME_COLUMN_NAME = "millisSinceEpoch";
  private static final int NUM_SKILLS = 28;
  private static final int NUM_MATCHING_SKILLS = 4;
  private static final int NUM_RECORDS = NUM_SKILLS * 1000;
  private static final int NUM_MATCHING_RECORDS = NUM_MATCHING_SKILLS * 1000;
  private static final int NUM_MATCHING_RECORDS_NATIVE = 7000;

  private static final String TEST_TEXT_COLUMN_QUERY =
      "SELECT COUNT(*) FROM %s WHERE TEXT_MATCH(skills, '\"machine learning\" AND spark')";

  private static final String TEST_TEXT_COLUMN_QUERY_NATIVE =
      "SELECT COUNT(*) FROM %s WHERE TEXT_CONTAINS(skills_native, 'm.*') AND TEXT_CONTAINS(skills_native, "
          + "'spark')";

  @Override
  public String getTimeColumnName() {
    return TIME_COLUMN_NAME;
  }

  @Nullable
  @Override
  protected String getSortedColumn() {
    return null;
  }

  @Override
  protected List<String> getNoDictionaryColumns() {
    return List.of(TEXT_COLUMN_NULL_NAME, TEXT_COLUMN_NAME, TEXT_COLUMN_NAME_CASE_SENSITIVE);
  }

  @Override
  protected List<FieldConfig> getFieldConfigs() {
    ObjectNode textColumnIndexes;
    try {
      textColumnIndexes = (ObjectNode) OBJECT_MAPPER.readTree("{\"text\": {}}");
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }

    FieldConfig nullableTextConfig =
        new FieldConfig(TEXT_COLUMN_NULL_NAME, FieldConfig.EncodingType.RAW, null, null, null, null, textColumnIndexes,
            null,
            null);

    FieldConfig textColumnFieldConfig =
        new FieldConfig(TEXT_COLUMN_NAME, FieldConfig.EncodingType.RAW, null, null, null, null, textColumnIndexes, null,
            null);

    ObjectNode textColumnCaseSensitiveIndexes;
    try {
      textColumnCaseSensitiveIndexes = (ObjectNode) OBJECT_MAPPER.readTree(
          "{"
              + "  \"text\": "
              + "  {"
              + "    \"caseSensitive\": \"true\""
              + "  }"
              + "}"
      );
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
    FieldConfig textColumnCaseSensitiveFieldConfig =
        new FieldConfig(TEXT_COLUMN_NAME_CASE_SENSITIVE, FieldConfig.EncodingType.RAW, null, null, null, null,
            textColumnCaseSensitiveIndexes, null, null);

    ObjectNode textColumnNativeIndexes;
    try {
      textColumnNativeIndexes = (ObjectNode) OBJECT_MAPPER.readTree(
          "{"
              + "  \"text\": "
              + "  {"
              + "    \"fst\": \"NATIVE\""
              + "  }"
              + "}"
      );
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
    FieldConfig textColumnNativeFieldConfig =
        new FieldConfig(TEXT_COLUMN_NAME_NATIVE, FieldConfig.EncodingType.RAW, null, null, null, null,
            textColumnNativeIndexes, null, null);
    return Arrays.asList(nullableTextConfig, textColumnFieldConfig, textColumnCaseSensitiveFieldConfig,
        textColumnNativeFieldConfig);
  }

  @Override
  public String getTableName() {
    return DEFAULT_TABLE_NAME;
  }

  @Override
  public Schema createSchema() {
    return new Schema.SchemaBuilder()
        .setSchemaName(getTableName())
        .setEnableColumnBasedNullHandling(true)
        .addDimensionField(TEXT_COLUMN_NULL_NAME, FieldSpec.DataType.STRING, field -> {
          field.setNullable(true);
          field.setDefaultNullValue(null);
        })
        .addSingleValueDimension(TEXT_COLUMN_NAME, FieldSpec.DataType.STRING)
        .addSingleValueDimension(TEXT_COLUMN_NAME_CASE_SENSITIVE, FieldSpec.DataType.STRING)
        .addSingleValueDimension(TEXT_COLUMN_NAME_NATIVE, FieldSpec.DataType.STRING)
        .addDateTime(TIME_COLUMN_NAME, FieldSpec.DataType.LONG, "1:MILLISECONDS:EPOCH", "1:MILLISECONDS").build();
  }

  @Override
  protected long getCountStarResult() {
    return NUM_RECORDS;
  }

  @Override
  public List<File> createAvroFiles()
      throws Exception {
    // Read all skills from the skill file
    InputStream inputStream = getClass().getClassLoader().getResourceAsStream("data/text_search_data/skills.txt");
    assertNotNull(inputStream);
    List<String> skills = new ArrayList<>(NUM_SKILLS);
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
      String line;
      while ((line = reader.readLine()) != null) {
        skills.add(line);
      }
    }
    assertEquals(skills.size(), NUM_SKILLS);

    File avroFile = new File(_tempDir, "data.avro");
    org.apache.avro.Schema avroSchema = org.apache.avro.Schema.createRecord("myRecord", null, null, false);
    avroSchema.setFields(Arrays.asList(
        new Field(TEXT_COLUMN_NULL_NAME, createUnion(create(Type.NULL), create(Type.STRING)), null, null),
        new Field(TEXT_COLUMN_NAME,
            create(Type.STRING), null, null),
        new Field(TEXT_COLUMN_NAME_CASE_SENSITIVE,
            create(Type.STRING), null, null),
        new Field(TEXT_COLUMN_NAME_NATIVE,
            create(Type.STRING), null, null),
        new Field(TIME_COLUMN_NAME,
            create(Type.LONG), null, null)));
    try (DataFileWriter<GenericData.Record> fileWriter = new DataFileWriter<>(new GenericDatumWriter<>(avroSchema))) {
      fileWriter.create(avroSchema, avroFile);
      for (int i = 0; i < NUM_RECORDS; i++) {
        GenericData.Record record = new GenericData.Record(avroSchema);
        record.put(TEXT_COLUMN_NULL_NAME, i % 2 == 0 ? null : skills.get(i % NUM_SKILLS));
        record.put(TEXT_COLUMN_NAME, skills.get(i % NUM_SKILLS));
        record.put(TEXT_COLUMN_NAME_CASE_SENSITIVE, skills.get(i % NUM_SKILLS));
        record.put(TEXT_COLUMN_NAME_NATIVE, skills.get(i % NUM_SKILLS));
        record.put(TIME_COLUMN_NAME, System.currentTimeMillis());
        fileWriter.append(record);
      }
    }
    return List.of(avroFile);
  }

  @Override
  public TableConfig createOfflineTableConfig() {
    return new TableConfigBuilder(TableType.OFFLINE)
        .setTableName(getTableName())
        .setTimeColumnName(getTimeColumnName())
        .setSortedColumn(getSortedColumn())
        .setInvertedIndexColumns(getInvertedIndexColumns())
        .setNoDictionaryColumns(getNoDictionaryColumns())
        .setRangeIndexColumns(getRangeIndexColumns())
        .setBloomFilterColumns(getBloomFilterColumns())
        .setFieldConfigList(getFieldConfigs())
        .setNumReplicas(getNumReplicas())
        .setSegmentVersion(getSegmentVersion())
        .setLoadMode(getLoadMode())
        .setTaskConfig(getTaskConfig())
        .setIngestionConfig(getIngestionConfig())
        .setQueryConfig(getQueryConfig())
        .setNullHandlingEnabled(false)
        .build();
  }

  @Test(dataProvider = "useBothQueryEngines")
  public void testTextSearchCountQuery(boolean useMultiStageQueryEngine)
      throws Exception {
    setUseMultiStageQueryEngine(useMultiStageQueryEngine);
    // Keep posting queries until all records are consumed
    long previousResult = 0;
    while (getCurrentCountStarResult() < NUM_RECORDS) {
      long result = getTextColumnQueryResult(String.format(TEST_TEXT_COLUMN_QUERY, getTableName()));
      assertTrue(result >= previousResult);
      previousResult = result;
      Thread.sleep(100);
    }

    //Lucene index on consuming segments to update the latest records
    TestUtils.waitForCondition(aVoid -> {
      try {
        return getTextColumnQueryResult(String.format(TEST_TEXT_COLUMN_QUERY, getTableName())) == NUM_MATCHING_RECORDS;
      } catch (Exception e) {
        fail("Caught exception while getting text column query result");
        return false;
      }
    }, 10_000L, "Failed to reach expected number of matching records");
  }

  @Test(dataProvider = "useBothQueryEngines")
  public void testTextSearchCountQueryNative(boolean useMultiStageQueryEngine)
      throws Exception {
    setUseMultiStageQueryEngine(useMultiStageQueryEngine);
    // Keep posting queries until all records are consumed
    long previousResult = 0;
    while (getCurrentCountStarResult() < NUM_RECORDS) {
      long result = getTextColumnQueryResult(String.format(TEST_TEXT_COLUMN_QUERY_NATIVE, getTableName()));
      assertTrue(result >= previousResult);
      previousResult = result;
      Thread.sleep(100);
    }

    assertEquals(getTextColumnQueryResult(String.format(TEST_TEXT_COLUMN_QUERY_NATIVE, getTableName())),
        NUM_MATCHING_RECORDS_NATIVE);
  }

  @Test(dataProvider = "useBothQueryEngines")
  public void testTextSearchCountQueryCaseSensitive(boolean useMultiStageQueryEngine)
      throws Exception {
    setUseMultiStageQueryEngine(useMultiStageQueryEngine);
    // Keep posting queries until all records are consumed
    long previousResult = 0;

    String queryWithMatch = "SELECT COUNT(*) FROM %s WHERE TEXT_MATCH(skills_case_sensitive, 'Java')";
    String queryWithoutMatch = "SELECT COUNT(*) FROM %s WHERE TEXT_MATCH(skills_case_sensitive, 'java')";
    while (getCurrentCountStarResult() < NUM_RECORDS) {
      long result = getTextColumnQueryResult(String.format(queryWithMatch, getTableName()));
      assertTrue(result >= previousResult);
      previousResult = result;
      Thread.sleep(100);
    }

    assertEquals(getTextColumnQueryResult(String.format(queryWithMatch, getTableName())), 12000);
    // Test case sensitive match, all skills are 'Java' not 'java'
    assertEquals(getTextColumnQueryResult(String.format(queryWithoutMatch, getTableName())), 0);
  }

  private long getTextColumnQueryResult(String query)
      throws Exception {
    return postQuery(query).get("resultTable").get("rows").get(0).get(0).asLong();
  }
}
