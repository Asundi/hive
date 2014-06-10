/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.serde2.avro;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.rmi.server.UID;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Type;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericData.Fixed;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.util.Utf8;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.serde2.objectinspector.StandardUnionObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.JavaHiveDecimalObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.typeinfo.DecimalTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.ListTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.MapTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.PrimitiveTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.StructTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.UnionTypeInfo;
import org.apache.hadoop.io.Writable;

class AvroDeserializer {
  private static final Log LOG = LogFactory.getLog(AvroDeserializer.class);
  /**
   * Set of already seen and valid record readers IDs which doesn't need re-encoding
   */
  private final HashSet<UID> noEncodingNeeded = new HashSet<UID>();
  /**
   * Map of record reader ID and the associated re-encoder. It contains only the record readers
   *  that record needs to be re-encoded.
   */
  private final HashMap<UID, SchemaReEncoder> reEncoderCache = new HashMap<UID, SchemaReEncoder>();
  /**
   * Flag to print the re-encoding warning message only once. Avoid excessive logging for each
   * record encoding.
   */
  private static boolean warnedOnce = false;
  /**
   * When encountering a record with an older schema than the one we're trying
   * to read, it is necessary to re-encode with a reader against the newer schema.
   * Because Hive doesn't provide a way to pass extra information to the
   * inputformat, we're unable to provide the newer schema when we have it and it
   * would be most useful - when the inputformat is reading the file.
   *
   * This is a slow process, so we try to cache as many of the objects as possible.
   */
  static class SchemaReEncoder {
    private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    private final GenericDatumWriter<GenericRecord> gdw = new GenericDatumWriter<GenericRecord>();
    private BinaryDecoder binaryDecoder = null;

    GenericDatumReader<GenericRecord> gdr = null;

    public SchemaReEncoder(Schema writer, Schema reader) {
      gdr = new GenericDatumReader<GenericRecord>(writer, reader);
    }

    public GenericRecord reencode(GenericRecord r)
        throws AvroSerdeException {
      baos.reset();

      BinaryEncoder be = EncoderFactory.get().directBinaryEncoder(baos, null);
      gdw.setSchema(r.getSchema());
      try {
        gdw.write(r, be);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());

        binaryDecoder = DecoderFactory.defaultFactory().createBinaryDecoder(bais, binaryDecoder);

        return gdr.read(r, binaryDecoder);

      } catch (IOException e) {
        throw new AvroSerdeException("Exception trying to re-encode record to new schema", e);
      }
    }
  }

  private List<Object> row;

  /**
   * Deserialize an Avro record, recursing into its component fields and
   * deserializing them as well.  Fields of the record are matched by name
   * against fields in the Hive row.
   *
   * Because Avro has some data types that Hive does not, these are converted
   * during deserialization to types Hive will work with.
   *
   * @param columnNames List of columns Hive is expecting from record.
   * @param columnTypes List of column types matched by index to names
   * @param writable Instance of GenericAvroWritable to deserialize
   * @param readerSchema Schema of the writable to deserialize
   * @return A list of objects suitable for Hive to work with further
   * @throws AvroSerdeException For any exception during deseriliazation
   */
  public Object deserialize(List<String> columnNames, List<TypeInfo> columnTypes,
                            Writable writable, Schema readerSchema) throws AvroSerdeException {
    if(!(writable instanceof AvroGenericRecordWritable)) {
      throw new AvroSerdeException("Expecting a AvroGenericRecordWritable");
    }

    if(row == null || row.size() != columnNames.size()) {
      row = new ArrayList<Object>(columnNames.size());
    } else {
      row.clear();
    }

    AvroGenericRecordWritable recordWritable = (AvroGenericRecordWritable) writable;
    GenericRecord r = recordWritable.getRecord();
    Schema fileSchema = recordWritable.getFileSchema();

   UID recordReaderId = recordWritable.getRecordReaderID();
   //If the record reader (from which the record is originated) is already seen and valid,
    //no need to re-encode the record.
    if(!noEncodingNeeded.contains(recordReaderId)) {
      SchemaReEncoder reEncoder = null;
      //Check if the record record is already encoded once. If it does
      //reuse the encoder.
      if(reEncoderCache.containsKey(recordReaderId)) {
        reEncoder = reEncoderCache.get(recordReaderId); //Reuse the re-encoder
      } else if (!r.getSchema().equals(readerSchema)) { //Evolved schema?
        //Create and store new encoder in the map for re-use
        reEncoder = new SchemaReEncoder(r.getSchema(), readerSchema);
        reEncoderCache.put(recordReaderId, reEncoder);
      } else{
        LOG.info("Adding new valid RRID :" +  recordReaderId);
        noEncodingNeeded.add(recordReaderId);
      }
      if(reEncoder != null) {
        if (!warnedOnce) {
          LOG.warn("Received different schemas.  Have to re-encode: " +
              r.getSchema().toString(false) + "\nSIZE" + reEncoderCache + " ID " + recordReaderId);
          warnedOnce = true;
        }
        r = reEncoder.reencode(r);
      }
    }

    workerBase(row, fileSchema, columnNames, columnTypes, r);
    return row;
  }

  // The actual deserialization may involve nested records, which require recursion.
  private List<Object> workerBase(List<Object> objectRow, Schema fileSchema, List<String> columnNames,
                                  List<TypeInfo> columnTypes, GenericRecord record)
          throws AvroSerdeException {
    for(int i = 0; i < columnNames.size(); i++) {
      TypeInfo columnType = columnTypes.get(i);
      String columnName = columnNames.get(i);
      Object datum = record.get(columnName);
      Schema datumSchema = record.getSchema().getField(columnName).schema();
      Schema.Field field = fileSchema.getField(columnName);
      objectRow.add(worker(datum, field == null ? null : field.schema(), datumSchema, columnType));
    }

    return objectRow;
  }

  private Object worker(Object datum, Schema fileSchema, Schema recordSchema, TypeInfo columnType)
          throws AvroSerdeException {
    // Klaxon! Klaxon! Klaxon!
    // Avro requires NULLable types to be defined as unions of some type T
    // and NULL.  This is annoying and we're going to hide it from the user.
    if(AvroSerdeUtils.isNullableType(recordSchema)) {
      return deserializeNullableUnion(datum, fileSchema, recordSchema, columnType);
    }


    switch(columnType.getCategory()) {
    case STRUCT:
      return deserializeStruct((GenericData.Record) datum, fileSchema, (StructTypeInfo) columnType);
    case UNION:
      return deserializeUnion(datum, fileSchema, recordSchema, (UnionTypeInfo) columnType);
    case LIST:
      return deserializeList(datum, fileSchema, recordSchema, (ListTypeInfo) columnType);
    case MAP:
      return deserializeMap(datum, fileSchema, recordSchema, (MapTypeInfo) columnType);
    case PRIMITIVE:
      return deserializePrimitive(datum, fileSchema, recordSchema, (PrimitiveTypeInfo) columnType);
    default:
      throw new AvroSerdeException("Unknown TypeInfo: " + columnType.getCategory());
    }
  }

  private Object deserializePrimitive(Object datum, Schema fileSchema, Schema recordSchema,
      PrimitiveTypeInfo columnType) throws AvroSerdeException {
    switch (columnType.getPrimitiveCategory()){
    case STRING:
      return datum.toString(); // To workaround AvroUTF8
      // This also gets us around the Enum issue since we just take the value
      // and convert it to a string. Yay!
    case BINARY:
      if (recordSchema.getType() == Type.FIXED){
        Fixed fixed = (Fixed) datum;
        return fixed.bytes();
      } else if (recordSchema.getType() == Type.BYTES){
        return AvroSerdeUtils.getBytesFromByteBuffer((ByteBuffer) datum);
      } else {
        throw new AvroSerdeException("Unexpected Avro schema for Binary TypeInfo: " + recordSchema.getType());
      }
    case DECIMAL:
      if (fileSchema == null) {
        throw new AvroSerdeException("File schema is missing for decimal field. Reader schema is " + columnType);
      }

      int scale = 0;
      try {
        scale = fileSchema.getJsonProp(AvroSerDe.AVRO_PROP_SCALE).getIntValue();
      } catch(Exception ex) {
        throw new AvroSerdeException("Failed to obtain scale value from file schema: " + fileSchema, ex);
      }

      HiveDecimal dec = AvroSerdeUtils.getHiveDecimalFromByteBuffer((ByteBuffer) datum, scale);
      JavaHiveDecimalObjectInspector oi = (JavaHiveDecimalObjectInspector)
          PrimitiveObjectInspectorFactory.getPrimitiveJavaObjectInspector((DecimalTypeInfo)columnType);
      return oi.set(null, dec);
    default:
      return datum;
    }
  }

  /**
   * Extract either a null or the correct type from a Nullable type.  This is
   * horrible in that we rebuild the TypeInfo every time.
   */
  private Object deserializeNullableUnion(Object datum, Schema fileSchema, Schema recordSchema,
                                          TypeInfo columnType) throws AvroSerdeException {
    int tag = GenericData.get().resolveUnion(recordSchema, datum); // Determine index of value
    Schema schema = recordSchema.getTypes().get(tag);
    if(schema.getType().equals(Schema.Type.NULL)) {
      return null;
    }

    return worker(datum, fileSchema == null ? null : fileSchema.getTypes().get(tag), schema,
        SchemaToTypeInfo.generateTypeInfo(schema));

  }

  private Object deserializeStruct(GenericData.Record datum, Schema fileSchema, StructTypeInfo columnType)
          throws AvroSerdeException {
    // No equivalent Java type for the backing structure, need to recurse and build a list
    ArrayList<TypeInfo> innerFieldTypes = columnType.getAllStructFieldTypeInfos();
    ArrayList<String> innerFieldNames = columnType.getAllStructFieldNames();
    List<Object> innerObjectRow = new ArrayList<Object>(innerFieldTypes.size());

    return workerBase(innerObjectRow, fileSchema, innerFieldNames, innerFieldTypes, datum);
  }

  private Object deserializeUnion(Object datum, Schema fileSchema, Schema recordSchema,
                                  UnionTypeInfo columnType) throws AvroSerdeException {
    int tag = GenericData.get().resolveUnion(recordSchema, datum); // Determine index of value
    Object desered = worker(datum, fileSchema == null ? null : fileSchema.getTypes().get(tag),
        recordSchema.getTypes().get(tag), columnType.getAllUnionObjectTypeInfos().get(tag));
    return new StandardUnionObjectInspector.StandardUnion((byte)tag, desered);
  }

  private Object deserializeList(Object datum, Schema fileSchema, Schema recordSchema,
                                 ListTypeInfo columnType) throws AvroSerdeException {
    // Need to check the original schema to see if this is actually a Fixed.
    if(recordSchema.getType().equals(Schema.Type.FIXED)) {
    // We're faking out Hive to work through a type system impedence mismatch.
    // Pull out the backing array and convert to a list.
      GenericData.Fixed fixed = (GenericData.Fixed) datum;
      List<Byte> asList = new ArrayList<Byte>(fixed.bytes().length);
      for(int j = 0; j < fixed.bytes().length; j++) {
        asList.add(fixed.bytes()[j]);
      }
      return asList;
    } else if(recordSchema.getType().equals(Schema.Type.BYTES)) {
      // This is going to be slow... hold on.
      ByteBuffer bb = (ByteBuffer)datum;
      List<Byte> asList = new ArrayList<Byte>(bb.capacity());
      byte[] array = bb.array();
      for(int j = 0; j < array.length; j++) {
        asList.add(array[j]);
      }
      return asList;
    } else { // An actual list, deser its values
      List listData = (List) datum;
      Schema listSchema = recordSchema.getElementType();
      List<Object> listContents = new ArrayList<Object>(listData.size());
      for(Object obj : listData) {
        listContents.add(worker(obj, fileSchema == null ? null : fileSchema.getElementType(), listSchema,
            columnType.getListElementTypeInfo()));
      }
      return listContents;
    }
  }

  private Object deserializeMap(Object datum, Schema fileSchema, Schema mapSchema, MapTypeInfo columnType)
          throws AvroSerdeException {
    // Avro only allows maps with Strings for keys, so we only have to worry
    // about deserializing the values
    Map<String, Object> map = new HashMap<String, Object>();
    Map<Utf8, Object> mapDatum = (Map)datum;
    Schema valueSchema = mapSchema.getValueType();
    TypeInfo valueTypeInfo = columnType.getMapValueTypeInfo();
    for (Utf8 key : mapDatum.keySet()) {
      Object value = mapDatum.get(key);
      map.put(key.toString(), worker(value, fileSchema == null ? null : fileSchema.getValueType(),
          valueSchema, valueTypeInfo));
    }

    return map;
  }

  public HashSet<UID> getNoEncodingNeeded() {
    return noEncodingNeeded;
  }

  public HashMap<UID, SchemaReEncoder> getReEncoderCache() {
    return reEncoderCache;
  }

}
