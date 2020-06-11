/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.connect.jdbc.sink.metadata;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.ConnectException;

import java.util.*;

import io.confluent.connect.jdbc.sink.JdbcSinkConfig;
import org.apache.kafka.connect.header.Headers;

public class FieldsMetadata {

  public final Set<String> keyFieldNames;
  //FLATTEN:
  public final Set<String> keyFieldNamesInKey;
  public final Set<String> nonKeyFieldNames;
  public final Map<String, SinkRecordField> allFields;

  private FieldsMetadata(
      Set<String> keyFieldNames,
      Set<String> nonKeyFieldNames,
      Map<String, SinkRecordField> allFields
  ) {
    boolean fieldCountsMatch = (keyFieldNames.size() + nonKeyFieldNames.size() == allFields.size());
    boolean allFieldsContained = (allFields.keySet().containsAll(keyFieldNames)
                   && allFields.keySet().containsAll(nonKeyFieldNames));
    if (!fieldCountsMatch || !allFieldsContained) {
      throw new IllegalArgumentException(String.format(
          "Validation fail -- keyFieldNames:%s nonKeyFieldNames:%s allFields:%s",
          keyFieldNames, nonKeyFieldNames, allFields
      ));
    }
    this.keyFieldNames = keyFieldNames;
    this.nonKeyFieldNames = nonKeyFieldNames;
    this.allFields = allFields;
    //FLATTEN:
    this.keyFieldNamesInKey = null;
  }

  //FLATTEN:
  private FieldsMetadata(
          Set<String> keyFieldNames,
          Set<String> keyFieldNamesInKey,
          Set<String> nonKeyFieldNames,
          Map<String, SinkRecordField> allFields
  ) {
    boolean fieldCountsMatch = (keyFieldNames.size() + nonKeyFieldNames.size() == allFields.size());
    boolean allFieldsContained = (allFields.keySet().containsAll(keyFieldNames)
            && allFields.keySet().containsAll(nonKeyFieldNames));
    if (!fieldCountsMatch || !allFieldsContained) {
      throw new IllegalArgumentException(String.format(
              "Validation fail -- keyFieldNames:%s nonKeyFieldNames:%s allFields:%s",
              keyFieldNames, nonKeyFieldNames, allFields
      ));
    }
    this.keyFieldNames = keyFieldNames;
    this.keyFieldNamesInKey = keyFieldNamesInKey;
    this.nonKeyFieldNames = nonKeyFieldNames;
    this.allFields = allFields;
  }

  public static FieldsMetadata extract(
      final String tableName,
      final JdbcSinkConfig.PrimaryKeyMode pkMode,
      final List<String> configuredPkFields,
      final Set<String> fieldsWhitelist,
      final SchemaPair schemaPair
  ) {
    return extract(
        tableName,
        pkMode,
        configuredPkFields,
        fieldsWhitelist,
        schemaPair.keySchema,
        schemaPair.valueSchema
    );
  }

  public static FieldsMetadata extract(
          final String tableName,
          final JdbcSinkConfig.PrimaryKeyMode pkMode,
          final List<String> configuredPkFields,
          final Set<String> fieldsWhitelist,
          final Schema keySchema,
          final Schema valueSchema
  ) {
    if (valueSchema != null && valueSchema.type() != Schema.Type.STRUCT) {
      throw new ConnectException("Value schema must be of type Struct");
    }

    final Map<String, SinkRecordField> allFields = new HashMap<>();

    final Set<String> keyFieldNames = new LinkedHashSet<>();
    switch (pkMode) {
      case NONE:
        break;

      case KAFKA:
        extractKafkaPk(tableName, configuredPkFields, allFields, keyFieldNames);
        break;

      case RECORD_KEY:
        extractRecordKeyPk(tableName, configuredPkFields, keySchema, allFields, keyFieldNames);
        break;

      case RECORD_VALUE:
        extractRecordValuePk(tableName, configuredPkFields, valueSchema, allFields, keyFieldNames);
        break;

      default:
        throw new ConnectException("Unknown primary key mode: " + pkMode);
    }

    final Set<String> nonKeyFieldNames = new LinkedHashSet<>();
    if (valueSchema != null) {
      for (Field field : valueSchema.fields()) {
        if (keyFieldNames.contains(field.name())) {
          continue;
        }
        if (!fieldsWhitelist.isEmpty() && !fieldsWhitelist.contains(field.name())) {
          continue;
        }

        nonKeyFieldNames.add(field.name());

        final Schema fieldSchema = field.schema();
        allFields.put(field.name(), new SinkRecordField(fieldSchema, field.name(), false));
      }
    }

    if (allFields.isEmpty()) {
      throw new ConnectException(
              "No fields found using key and value schemas for table: " + tableName
      );
    }

    return new FieldsMetadata(keyFieldNames, nonKeyFieldNames, allFields);
  }

  //FLATTEN:
  public static FieldsMetadata extract(
      final String tableName,
      final JdbcSinkConfig.PrimaryKeyMode pkMode,
      final SchemaPair schemaPair,
      final Headers headers,
      boolean deleteEnabled,
      JdbcSinkConfig.InsertMode insertMode
  ) {
    if (schemaPair.valueSchema != null && schemaPair.valueSchema.type() != Schema.Type.STRUCT) {
      throw new ConnectException("Value schema must be of type Struct");
    }

    final Map<String, SinkRecordField> allFields = new HashMap<>();

    final Set<String> keyFieldNames = new LinkedHashSet<>();
    final Set<String> keyFieldNamesInKey = new LinkedHashSet<>();
    if (pkMode == JdbcSinkConfig.PrimaryKeyMode.FLATTEN) {
        extractFlattenededPk(tableName, schemaPair.keySchema, schemaPair.valueSchema, allFields, keyFieldNames, keyFieldNamesInKey, headers, deleteEnabled, insertMode);
    } else {
      throw new ConnectException("Unknown primary key mode: " + pkMode);
    }

    final Set<String> nonKeyFieldNames = new LinkedHashSet<>();

    if (schemaPair.valueSchema != null) {
      for (Field field : schemaPair.valueSchema.fields()) {
        if (keyFieldNames.contains(field.name())) {
          continue;
        }
        nonKeyFieldNames.add(field.name());

        final Schema fieldSchema = field.schema();
        allFields.put(field.name(), new SinkRecordField(fieldSchema, field.name(), false));
      }
    }

    if (allFields.isEmpty()) {
      throw new ConnectException(
          "No fields found using key and value schemas for table: " + tableName
      );
    }
    return new FieldsMetadata(keyFieldNames, keyFieldNamesInKey, nonKeyFieldNames, allFields);
  }

  private static void extractKafkaPk(
      final String tableName,
      final List<String> configuredPkFields,
      final Map<String, SinkRecordField> allFields,
      final Set<String> keyFieldNames
  ) {
    if (configuredPkFields.isEmpty()) {
      keyFieldNames.addAll(JdbcSinkConfig.DEFAULT_KAFKA_PK_NAMES);
    } else if (configuredPkFields.size() == 3) {
      keyFieldNames.addAll(configuredPkFields);
    } else {
      throw new ConnectException(String.format(
          "PK mode for table '%s' is %s so there should either be no field names defined for "
          + "defaults %s to be applicable, or exactly 3, defined fields are: %s",
          tableName,
          JdbcSinkConfig.PrimaryKeyMode.KAFKA,
          JdbcSinkConfig.DEFAULT_KAFKA_PK_NAMES,
          configuredPkFields
      ));
    }
    final Iterator<String> it = keyFieldNames.iterator();
    final String topicFieldName = it.next();
    allFields.put(
        topicFieldName,
        new SinkRecordField(Schema.STRING_SCHEMA, topicFieldName, true)
    );
    final String partitionFieldName = it.next();
    allFields.put(
        partitionFieldName,
        new SinkRecordField(Schema.INT32_SCHEMA, partitionFieldName, true)
    );
    final String offsetFieldName = it.next();
    allFields.put(
        offsetFieldName,
        new SinkRecordField(Schema.INT64_SCHEMA, offsetFieldName, true)
    );
  }

  private static void extractRecordKeyPk(
      final String tableName,
      final List<String> configuredPkFields,
      final Schema keySchema,
      final Map<String, SinkRecordField> allFields,
      final Set<String> keyFieldNames
  ) {
    {
      if (keySchema == null) {
        throw new ConnectException(String.format(
            "PK mode for table '%s' is %s, but record key schema is missing",
            tableName,
            JdbcSinkConfig.PrimaryKeyMode.RECORD_KEY
        ));
      }
      final Schema.Type keySchemaType = keySchema.type();
      if (keySchemaType.isPrimitive()) {
        if (configuredPkFields.size() != 1) {
          throw new ConnectException(String.format(
              "Need exactly one PK column defined since the key schema for records is a "
              + "primitive type, defined columns are: %s",
              configuredPkFields
          ));
        }
        final String fieldName = configuredPkFields.get(0);
        keyFieldNames.add(fieldName);
        allFields.put(fieldName, new SinkRecordField(keySchema, fieldName, true));
      } else if (keySchemaType == Schema.Type.STRUCT) {
        if (configuredPkFields.isEmpty()) {
          for (Field keyField : keySchema.fields()) {
            keyFieldNames.add(keyField.name());
          }
        } else {
          for (String fieldName : configuredPkFields) {
            final Field keyField = keySchema.field(fieldName);
            if (keyField == null) {
              throw new ConnectException(String.format(
                  "PK mode for table '%s' is %s with configured PK fields %s, but record key "
                  + "schema does not contain field: %s",
                  tableName, JdbcSinkConfig.PrimaryKeyMode.RECORD_KEY, configuredPkFields, fieldName
              ));
            }
          }
          keyFieldNames.addAll(configuredPkFields);
        }
        for (String fieldName : keyFieldNames) {
          final Schema fieldSchema = keySchema.field(fieldName).schema();
          allFields.put(fieldName, new SinkRecordField(fieldSchema, fieldName, true));
        }
      } else {
        throw new ConnectException(
            "Key schema must be primitive type or Struct, but is of type: " + keySchemaType
        );
      }
    }
  }

  private static void extractRecordValuePk(
      final String tableName,
      final List<String> configuredPkFields,
      final Schema valueSchema,
      final Map<String, SinkRecordField> allFields,
      final Set<String> keyFieldNames
  ) {
    if (valueSchema == null) {
      throw new ConnectException(String.format(
          "PK mode for table '%s' is %s, but record value schema is missing",
          tableName,
          JdbcSinkConfig.PrimaryKeyMode.RECORD_VALUE)
      );
    }
    if (configuredPkFields.isEmpty()) {
      for (Field keyField : valueSchema.fields()) {
        keyFieldNames.add(keyField.name());
      }
    } else {
      for (String fieldName : configuredPkFields) {
        if (valueSchema.field(fieldName) == null) {
          throw new ConnectException(String.format(
              "PK mode for table '%s' is %s with configured PK fields %s, but record value "
              + "schema does not contain field: %s",
              tableName, JdbcSinkConfig.PrimaryKeyMode.RECORD_VALUE, configuredPkFields, fieldName
          ));
        }
      }
      keyFieldNames.addAll(configuredPkFields);
    }
    for (String fieldName : keyFieldNames) {
      final Schema fieldSchema = valueSchema.field(fieldName).schema();
      allFields.put(fieldName, new SinkRecordField(fieldSchema, fieldName, true));
    }
  }

  //FLATTEN:
  private static void extractFlattenededPk(
          final String tableName,
          final Schema keySchema,
          final Schema valueSchema,
          final Map<String, SinkRecordField> allFields,
          final Set<String> keyFieldNames,
          final Set<String> keyFieldNamesInKey,
          final Headers headers,
          boolean deleteEnabled,
          JdbcSinkConfig.InsertMode insertMode

  ) {
    if (valueSchema == null && (deleteEnabled || insertMode == JdbcSinkConfig.InsertMode.UPSERT)) {
      if (keySchema == null) {
        throw new ConnectException(String.format(
                "PK mode for table '%s' is %s, but record key schema is missing",
                tableName,
                JdbcSinkConfig.PrimaryKeyMode.FLATTEN
        ));
      }
      final Schema.Type keySchemaType = keySchema.type();
      if (keySchemaType.isPrimitive()) {
        if (headers.size() != 1) {
          throw new ConnectException(String.format(
                  "Need exactly one PK column defined since the key schema for records is a "
                          + "primitive type, defined columns are: %s",
                  headers
          ));
        }
        final List<String> fieldName = new ArrayList<>();
        headers.forEach(h -> fieldName.add(h.value().toString()));
        keyFieldNames.add(fieldName.get(0));
        keyFieldNamesInKey.add(fieldName.get(0));
        allFields.put(fieldName.get(0), new SinkRecordField(keySchema, fieldName.get(0), true));
      } else if (keySchemaType == Schema.Type.STRUCT) {
          headers.forEach(h -> {
            final Field keyField = keySchema.field(h.key());
            if (keyField == null) {
              throw new ConnectException(String.format(
                      "PK mode for table '%s' is %s with configured PK fields %s, but record key "
                              + "schema does not contain field: %s",
                      tableName, JdbcSinkConfig.PrimaryKeyMode.FLATTEN, headers, h.key()
              ));
            }
            keyFieldNames.add(h.value().toString());
            keyFieldNamesInKey.add(h.value().toString());
            final Schema fieldSchema = keySchema.field(h.key()).schema();
            allFields.put(h.value().toString(), new SinkRecordField(fieldSchema, h.value().toString(), true));
          });
      } else {
        throw new ConnectException(
                "Key schema must be primitive type or Struct, but is of type: " + keySchemaType
        );
      }
    }
    else {
      if (valueSchema == null) {
        throw new ConnectException(String.format(
                "PK mode for table '%s' is %s, but record value schema is missing",
                tableName,
                JdbcSinkConfig.PrimaryKeyMode.FLATTEN)
        );
      }

      headers.forEach(h -> {
        if (valueSchema.field(h.value().toString()) == null) {
          throw new ConnectException(String.format(
                  "PK mode for table '%s' is %s with configured PK fields %s, but record value "
                          + "schema does not contain this field.",
                  tableName, JdbcSinkConfig.PrimaryKeyMode.FLATTEN, h.value().toString()
          ));
        } else {
          keyFieldNames.add(h.value().toString());
          if (keySchema != null) {
            if (keySchema.type() == Schema.Type.STRUCT) {
              keySchema.fields().stream().filter(kf -> kf.name().equals(h.key())).forEach(kf -> keyFieldNamesInKey.add(h.value().toString()));
            } else if (h.key().endsWith(".key")) {
              keyFieldNamesInKey.add(h.value().toString());
            }
          }
        }
      });
      for (String fieldName : keyFieldNames) {
        final Schema fieldSchema = valueSchema.field(fieldName).schema();
        allFields.put(fieldName, new SinkRecordField(fieldSchema, fieldName, true));
      }
    }
  }

  @Override
  public String toString() {
    return "FieldsMetadata{"
           + "keyFieldNames=" + keyFieldNames
            + ", keyFieldNamesInKey=" + keyFieldNamesInKey
           + ", nonKeyFieldNames=" + nonKeyFieldNames
           + ", allFields=" + allFields
           + '}';
  }
}
