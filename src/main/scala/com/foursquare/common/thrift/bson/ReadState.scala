// Copyright 2015 Foursquare Labs Inc. All Rights Reserved.

package com.foursquare.common.thrift.bson

import java.io.InputStream
import java.nio.ByteBuffer

import org.apache.thrift.TException

/**
 * Where all the parsing work is done
 * There are three types of objects that that be nested within each other: Struct, Map, and List
 * There's an associated implementation of ReadState for each one
 */
trait ReadState {
  def readI32(): Int
  def readI64(): Long
  def readDouble(): Double
  def readBool(): Boolean
  def readBinary(): ByteBuffer
  def readString(): String
  def readStruct(): StructReadState
  def readMap(): MapReadState
  def readList(): ListReadState
  def lastFieldType: Byte
  def lastFieldName: String
}

/** 
 * Helper functions for parsing out sub objects
 */
object ReadState {

  private def branchStream(inputStream: InputStream, parentSize: Int): BranchingInputStream = {
    def enforceSize(size: Int) {
      if (size > parentSize) {
        throw new TException(s"Parse error. Sub document can't be larger than parent. $size > $parentSize")
      }
    }
    // we need to copy all the bytes from the input stream
    // for each sub object into their instances of ReadState
    inputStream match {
      case is: BranchingInputStream =>
        is.mark(4)
        val size = StreamHelper.readInt(is)
        is.reset()
        is.branch(size)
      case _ =>
        // the mongo driver's InputStream doesn't support mark/reset.
        val size = StreamHelper.readInt(inputStream)
        val bytes = new Array[Byte](size)
        inputStream.read(bytes, 4, size - 4)
        StreamHelper.writeInt(bytes, 0, size)
        new BranchingInputStream(bytes, 0, size)
    }
  }

  // returns (bytesRead, bsonValueType, items)
  def bsonToTuples(inputStream: InputStream, buffer: ByteStringBuilder): (Int, Byte, Vector[(String, Any)]) = {
    var valueType: Byte = BSON.EOO
    // all elements in collection should be of the same type. track here
    def checkTypeConsistency(vType: Byte) {
      if (valueType != BSON.EOO && vType != valueType) {
        throw new TException(s"Collection elements must have same type. First was $valueType, now seeing $vType.")
      }
      valueType = vType
    }

    val readState = new StructReadState(inputStream, buffer)

    var vectorBuilder = Vector.newBuilder[(String, Any)]

    while (readState.hasAnotherField) {
      val (fieldName: String, bsonType: Byte) = readState.readFieldType()
      checkTypeConsistency(bsonType)
      val fieldValue: Any = bsonType match {
        case BSON.NUMBER_LONG | BSON.TIMESTAMP | BSON.DATE =>
          readState.readI64()
        case BSON.NUMBER =>
          readState.readDouble()
        case BSON.STRING | BSON.CODE =>
          readState.readString()
        case BSON.BINARY | BSON.OID =>
          readState.readBinary()
        case BSON.BOOLEAN =>
          readState.readBool()
        case BSON.NUMBER_INT =>
          readState.readI32()
        case BSON.OBJECT | BSON.ARRAY =>
          val branchingStream = branchStream(inputStream, readState.size)
          readState.bytesRead += branchingStream.available()
          branchingStream
        case BSON.NULL | BSON.MINKEY | BSON.MAXKEY => // zero bytes
        case _ => throw new UnsupportedOperationException("Invalid bson type " + bsonType)
      }
      vectorBuilder += (fieldName -> fieldValue)
    }
    readState.readEnd()
    (readState.size, valueType, vectorBuilder.result())
  }

  def bsonToMap(inputStream: InputStream, buffer: ByteStringBuilder): (Int, MapReadState) = {
    val resultTuple = bsonToTuples(inputStream, buffer)
    resultTuple._1 -> new MapReadState(resultTuple._3, buffer, resultTuple._2)
  }

  def bsonToList(inputStream: InputStream, buffer: ByteStringBuilder): (Int, ListReadState) = {
    val resultTuple = bsonToTuples(inputStream, buffer)
    resultTuple._1 -> new ListReadState(resultTuple._3, buffer, resultTuple._2)
  }
}

/**
 * stores parsing state for a Bson Document
 */
class StructReadState(inputStream: InputStream, buffer: ByteStringBuilder) extends ReadState {
  val size = StreamHelper.readInt(inputStream) - 4
  if (size < 0) {
    throw new TException(s"Document size less than zero $size")
  }
  if (size > StreamHelper.MaxDocSize) {
    throw new TException(s"Document size greater than maximum $size")
  }
  var bytesRead = 0

  // keep track of the last parsed field type so we can enforce that reads match field types
  var lastFieldType: Byte = 0x0
  var lastFieldName: String = ""

  private def enforceLastFieldType(fieldType: Byte) {
    if (fieldType != lastFieldType) {
      throw new TException(s"Requested field type $fieldType does not match parsed $lastFieldType for $lastFieldName.")
    }
  }

  private def _readInteger(): Int = {
    bytesRead += 4
    StreamHelper.readInt(inputStream)
  }
  
  private def _readByte(): Byte = {
    bytesRead += 1
    inputStream.read().asInstanceOf[Byte]
  }

  def hasAnotherField: Boolean = {
    val minFieldSize = 3 // type, single byte field name, null
    bytesRead < size - minFieldSize
  }

  def readFieldType(): (String, Byte) = {
    lastFieldType = _readByte()
    var keyByte = _readByte()

    var keySize = 0
    buffer.reset()
    while (keyByte > 0) {
      if (keySize > size) {
        throw new TException("Parse error. Field name can't be larger than document size. $keySize > $size")
      }
      buffer.append(keyByte)
      keySize += 1
      keyByte = _readByte()
    }
    lastFieldName = buffer.build()
    lastFieldName -> lastFieldType
  }

  def readEnd() {
    val nullByte = _readByte()
    if (BSON.EOO != nullByte) {
      throw new TException(s"Exepected null byte in readEnd, but got $nullByte.")
    }
    if (bytesRead != size) {
      throw new TException(s"readEnd called before struct fully read. Still have ${size - bytesRead} bytes remaining")
    }
  }

  def readI32(): Int = {
    enforceLastFieldType(BSON.NUMBER_INT)
    _readInteger()
  }

  def readI64(): Long = {
    enforceLastFieldType(BSON.NUMBER_LONG)
    bytesRead += 8
    StreamHelper.readLong(inputStream)
  }

  def readDouble(): Double = {
    enforceLastFieldType(BSON.NUMBER)
    bytesRead += 8
    java.lang.Double.longBitsToDouble(StreamHelper.readLong(inputStream))
  }

  def readBool(): Boolean = {
    enforceLastFieldType(BSON.BOOLEAN)
    _readByte() > 0
  }

  private def buildByteBuffer(length: Int): ByteBuffer = {
    val bytes = new Array[Byte](length)
    bytesRead += length
    inputStream.read(bytes, 0, length)
    ByteBuffer.wrap(bytes)
  }

  def readBinary(): ByteBuffer = {
    lastFieldType match {
      case BSON.OID =>
        val oidLength = 12
        buildByteBuffer(oidLength)
      case BSON.BINARY =>
        val length = _readInteger()
        if (length > size) {
          throw new TException("Parse error. Binary data can't be larger than document size. $length > $size")
        }
        // read and ignore the binary field type
        _readByte()
        buildByteBuffer(length)
      case _ => throw new TException(s"Unexpected binary field read. Last field type was $lastFieldType")
    }
  }

  def readString(): String = {
    enforceLastFieldType(BSON.STRING)
    val length = _readInteger()
    if (length > size) {
      throw new TException("Parse error. String length can't be larger than document size.")
    }
    buffer.reset()
    buffer.read(inputStream, length - 1)
    // verify the nullByte
    val nullByte = inputStream.read()
    if (nullByte != 0) {
      throw new TException("Parse error. Expected 0 byte at end of string but was $nullByte")
    }
    bytesRead += length
    buffer.build()
  }

  def readStruct(): StructReadState = {
    enforceLastFieldType(BSON.OBJECT)
    val structReadState = new StructReadState(inputStream, buffer)
    bytesRead += structReadState.size + 4
    structReadState
  }

  def readMap(): MapReadState = {
    enforceLastFieldType(BSON.OBJECT)
    val (size: Int, mapReadState: MapReadState) = ReadState.bsonToMap(inputStream, buffer)
    bytesRead += size + 4
    mapReadState
  }

  def readList(): ListReadState = {
    enforceLastFieldType(BSON.ARRAY)
    val (size: Int, listReadState: ListReadState) = ReadState.bsonToList(inputStream, buffer)
    bytesRead += size + 4
    listReadState
  }
}

/**
 * used by List and Map sub collections
 * we need to greedily parse out sub collections because thrift needs to know the number of elements ahead of time
 */
abstract class CollectionReadState(
  allItems: Vector[(String, Any)],
  buffer: ByteStringBuilder,
  val lastFieldType: Byte
) extends ReadState {
  def getCurrentValue(): Any

  def itemCount = allItems.size

  override def readI64(): Long = {
    getCurrentValue().asInstanceOf[Long]
  }

  override def readI32(): Int = {
    getCurrentValue().asInstanceOf[Int]
  }

  override def readDouble(): Double = {
    getCurrentValue().asInstanceOf[Double]
  }

  override def readBool(): Boolean = {
    getCurrentValue().asInstanceOf[Boolean]
  }

  override def readStruct(): StructReadState = {
    new StructReadState(getCurrentValue().asInstanceOf[BranchingInputStream], buffer)
  }

  override def readMap(): MapReadState = {
    ReadState.bsonToMap(getCurrentValue().asInstanceOf[BranchingInputStream], buffer)._2
  }

  override def readList(): ListReadState = {
    ReadState.bsonToList(getCurrentValue().asInstanceOf[BranchingInputStream], buffer)._2
  }
}

class MapReadState(
  allItems: Vector[(String, Any)],
  buffer: ByteStringBuilder,
  lastFieldType: Byte
) extends CollectionReadState(allItems, buffer, lastFieldType) {
  private var readCounter = 0

  var lastFieldName: String = ""

  def getCurrentPair(): (String, Any) = {
    readCounter += 1
    val pair = allItems( (readCounter - 1) / 2)
    lastFieldName = pair._1
    pair
  }

  override def getCurrentValue(): Any = {
    if (readCounter % 2 == 0) {
      throw new TException(s"Attempting to read value before key $readCounter")
    }
    getCurrentPair()._2
  }

  override def readString(): String = {
    // alternate between reading key names and ready values
    if (readCounter % 2 == 0) {
      getCurrentPair()._1
    } else {
      getCurrentValue().asInstanceOf[String]
    }
  }

  override def readBinary(): ByteBuffer = {
    // alternate between reading key names and ready values
    if (readCounter % 2 == 0) {
      ByteBuffer.wrap(getCurrentPair()._1.getBytes(ByteStringBuilder.UTF8_CHARSET))
    } else {
      getCurrentValue().asInstanceOf[ByteBuffer]
    }
  }
}

class ListReadState(
  allItems: Vector[(String, Any)],
  buffer: ByteStringBuilder,
  lastFieldType: Byte
) extends CollectionReadState(allItems, buffer, lastFieldType) {
  private var readCounter = 0

  def lastFieldName: String = ""

  def getCurrentValue(): Any = {
    readCounter += 1
    allItems(readCounter - 1)._2
  }

  override def readString(): String = {
    getCurrentValue().asInstanceOf[String]
  }

  override def readBinary(): ByteBuffer = {
    getCurrentValue().asInstanceOf[ByteBuffer]
  }
}