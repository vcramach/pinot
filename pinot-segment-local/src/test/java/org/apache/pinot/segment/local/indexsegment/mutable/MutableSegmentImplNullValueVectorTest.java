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
package org.apache.pinot.segment.local.indexsegment.mutable;

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.pinot.segment.local.PinotBuffersAfterClassCheckRule;
import org.apache.pinot.segment.local.segment.creator.TransformPipeline;
import org.apache.pinot.segment.spi.datasource.DataSource;
import org.apache.pinot.segment.spi.index.reader.NullValueVectorReader;
import org.apache.pinot.spi.config.table.TableConfig;
import org.apache.pinot.spi.config.table.TableType;
import org.apache.pinot.spi.data.Schema;
import org.apache.pinot.spi.data.readers.FileFormat;
import org.apache.pinot.spi.data.readers.GenericRow;
import org.apache.pinot.spi.data.readers.RecordReader;
import org.apache.pinot.spi.data.readers.RecordReaderFactory;
import org.apache.pinot.spi.utils.builder.TableConfigBuilder;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;


public class MutableSegmentImplNullValueVectorTest implements PinotBuffersAfterClassCheckRule {
  private static final String PINOT_SCHEMA_FILE_PATH = "data/test_null_value_vector_pinot_schema.json";
  private static final String DATA_FILE = "data/test_null_value_vector_data.json";

  private static MutableSegmentImpl _mutableSegmentImpl;
  private static List<String> _finalNullColumns;

  @BeforeClass
  public void setup()
      throws Exception {
    TableConfig tableConfig = new TableConfigBuilder(TableType.OFFLINE).setTableName("testTable").build();
    URL schemaResourceUrl = getClass().getClassLoader().getResource(PINOT_SCHEMA_FILE_PATH);
    assertNotNull(schemaResourceUrl);
    Schema schema = Schema.fromFile(new File(schemaResourceUrl.getFile()));
    TransformPipeline transformPipeline = new TransformPipeline(tableConfig, schema);
    URL dataResourceUrl = getClass().getClassLoader().getResource(DATA_FILE);
    assertNotNull(dataResourceUrl);
    File jsonFile = new File(dataResourceUrl.getFile());
    _mutableSegmentImpl =
        MutableSegmentImplTestUtils.createMutableSegmentImpl(schema, Collections.emptySet(), Collections.emptySet(),
            Collections.emptySet(), false, true);
    GenericRow reuse = new GenericRow();
    try (RecordReader recordReader = RecordReaderFactory.getRecordReader(FileFormat.JSON, jsonFile,
        schema.getColumnNames(), null)) {
      while (recordReader.hasNext()) {
        recordReader.next(reuse);
        TransformPipeline.Result result = transformPipeline.processRow(reuse);
        for (GenericRow transformedRow : result.getTransformedRows()) {
          _mutableSegmentImpl.index(transformedRow, null);
        }
        reuse.clear();
      }
    }
    _finalNullColumns = Arrays.asList("signup_email", "cityid");
  }

  @AfterClass
  public void tearDown() {
    _mutableSegmentImpl.destroy();
  }

  @Test
  public void testNullValueVector() {
    DataSource cityIdDataSource = _mutableSegmentImpl.getDataSource("cityid");
    DataSource descriptionDataSource = _mutableSegmentImpl.getDataSource("description");
    NullValueVectorReader cityIdNullValueVector = cityIdDataSource.getNullValueVector();
    NullValueVectorReader descNullValueVector = descriptionDataSource.getNullValueVector();
    assertFalse(cityIdNullValueVector.isNull(1));
    assertTrue(cityIdNullValueVector.isNull(0));
    assertFalse(descNullValueVector.isNull(0));
    assertFalse(descNullValueVector.isNull(1));
  }

  @Test
  public void testGetRecord() {
    GenericRow reuse = new GenericRow();
    _mutableSegmentImpl.getRecord(0, reuse);
    assertEquals(reuse.getNullValueFields(), _finalNullColumns);

    reuse.clear();
    _mutableSegmentImpl.getRecord(1, reuse);
    assertTrue(reuse.getNullValueFields().isEmpty());
  }
}
