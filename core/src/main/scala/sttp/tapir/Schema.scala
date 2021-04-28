package sttp.tapir

import sttp.model.Part
import sttp.tapir.SchemaType._
import sttp.tapir.generic.Derived
import sttp.tapir.internal.IterableToListMap

import java.io.InputStream
import java.math.{BigDecimal => JBigDecimal}
import java.nio.ByteBuffer
import java.time._
import java.util.{Date, UUID}
import scala.annotation.{StaticAnnotation, implicitNotFound}

/** Describes the type `T`: its low-level representation, meta-data and validation rules.
  * @param format The name of the format of the low-level representation of `T`.
  */
@implicitNotFound(
  msg = """Could not find Schema for type ${T}.
Since 0.17.0 automatic derivation requires the following import: `import sttp.tapir.generic.auto._`
You can find more details in the docs: https://tapir.softwaremill.com/en/latest/endpoint/customtypes.html#schema-derivation
When using datatypes integration remember to import respective schemas/codecs as described in https://tapir.softwaremill.com/en/latest/endpoint/integrations.html"""
)
case class Schema[T](
    schemaType: SchemaType[T],
    isOptional: Boolean = false,
    description: Option[String] = None,
    // The default value together with the value encoded to a raw format, which will then be directly rendered as a
    // string in documentation. This is needed as codecs for nested types aren't available. Similar to Validator.EncodeToRaw
    default: Option[(T, Option[Any])] = None,
    format: Option[String] = None,
    encodedExample: Option[Any] = None,
    deprecated: Boolean = false,
    validator: Validator[T] = Validator.pass[T]
) extends SchemaMacros[T] {

  def map[TT](f: T => Option[TT])(g: TT => T): Schema[TT] = copy(
    schemaType = schemaType.contramap(g),
    default = default.flatMap { case (t, raw) =>
      f(t).map(tt => (tt, raw))
    },
    validator = validator.contramap(g)
  )

  /** Returns an optional version of this schema, with `isOptional` set to true. */
  def asOption: Schema[Option[T]] =
    Schema(
      schemaType = SOption(this)(identity),
      isOptional = true,
      format = format,
      deprecated = deprecated
    )

  /** Returns an array version of this schema, with the schema type wrapped in [[SArray]].
    * Sets `isOptional` to true as the collection might be empty.
    */
  def asArray: Schema[Array[T]] =
    Schema(
      schemaType = SArray(this)(_.toIterable),
      isOptional = true,
      deprecated = deprecated
    )

  /** Returns a collection version of this schema, with the schema type wrapped in [[SArray]].
    * Sets `isOptional` to true as the collection might be empty.
    */
  def asIterable[C[X] <: Iterable[X]]: Schema[C[T]] =
    Schema(
      schemaType = SArray(this)(identity),
      isOptional = true,
      deprecated = deprecated
    )

  def description(d: String): Schema[T] = copy(description = Some(d))

  def encodedExample(e: Any): Schema[T] = copy(encodedExample = Some(e))

  def default(t: T, raw: Option[Any] = None): Schema[T] = copy(default = Some((t, raw)), isOptional = true)

  def format(f: String): Schema[T] = copy(format = Some(f))

  def deprecated(d: Boolean): Schema[T] = copy(deprecated = d)

  def show: String = s"schema is $schemaType"

  def showValidators: Option[String] = {
    if (hasValidation) {
      val thisValidator = validator.show
      val childValidators = schemaType match {
        case SOption(element) => element.showValidators.map(esv => s"elements($esv)")
        case SArray(element)  => element.showValidators.map(esv => s"elements($esv)")
        case SProduct(_, fields) =>
          fields.map(f => f.schema.showValidators.map(fvs => s"${f.name.name}->($fvs)")).collect { case Some(s) => s } match {
            case Nil => None
            case l   => Some(l.mkString(","))
          }
        case SOpenProduct(_, valueSchema) => valueSchema.showValidators.map(esv => s"elements($esv)")
        case SCoproduct(_, subtypes, _) =>
          subtypes.map { case (n, v) => v.showValidators.map(svs => s"$n->($svs)") }.toList match {
            case Nil => None
            case l   => Some(l.mkString(","))
          }
        case SRef(_) => Some("recursive")
        case _       => None
      }

      (thisValidator.toList ++ childValidators.toList) match {
        case Nil => None
        case l   => Some(l.mkString(","))
      }
    } else None
  }

  def modifyUnsafe[U](fields: String*)(modify: Schema[U] => Schema[U]): Schema[T] = modifyAtPath(fields.toList, modify)

  private def modifyAtPath[U](fieldPath: List[String], modify: Schema[U] => Schema[U]): Schema[T] =
    fieldPath match {
      case Nil => modify(this.asInstanceOf[Schema[U]]).asInstanceOf[Schema[T]] // we don't have type-polymorphic functions
      case f :: fs =>
        val schemaType2 = schemaType match {
          case s @ SArray(element) if f == Schema.ModifyCollectionElements  => SArray(element.modifyAtPath(fs, modify))(s.toIterable)
          case s @ SOption(element) if f == Schema.ModifyCollectionElements => SOption(element.modifyAtPath(fs, modify))(s.toOption)
          case s @ SProduct(_, fields) =>
            s.copy(fields = fields.map { field =>
              if (field.name.name == f) SProductField[T, field.FieldType](field.name, field.schema.modifyAtPath(fs, modify), field.get)
              else field
            })
          case s @ SOpenProduct(_, valueSchema) if f == Schema.ModifyCollectionElements =>
            s.copy(valueSchema = valueSchema.modifyAtPath(fs, modify))(s.fieldValues)
          case s @ SCoproduct(_, subtypes, _) =>
            s.copy(subtypes = subtypes.mapValues(_.modifyAtPath(fieldPath, modify)).toListMap)(s.subtypeInfo)
          case _ => schemaType
        }
        copy(schemaType = schemaType2)
    }

  /** Add a validator to this schema. */
  def validate(v: Validator[T]): Schema[T] = copy(validator = validator.and(v))

  /** Apply defined validation rules to the given value. */
  def applyValidation(t: T): List[ValidationError[_]] = applyValidation(t, Map())

  private def applyValidation(t: T, objects: Map[SObjectInfo, Schema[_]]): List[ValidationError[_]] = {
    // we avoid running validation for structures where there are no validation rules applied (recursively)
    if (hasValidation) {
      validator(t) ++ (schemaType match {
        case s @ SOption(element) => s.toOption(t).toList.flatMap(element.applyValidation(_, objects))
        case s @ SArray(element)  => s.toIterable(t).flatMap(element.applyValidation(_, objects))
        case s @ SProduct(info, _) =>
          val objects2 = objects + (info -> this)
          s.fieldsWithValidation.flatMap(f => f.get(t).map(f.schema.applyValidation(_, objects2)).getOrElse(Nil).map(_.prependPath(f.name)))
        case s @ SOpenProduct(info, valueSchema) =>
          val objects2 = objects + (info -> this)
          s.fieldValues(t).flatMap { case (k, v) => valueSchema.applyValidation(v, objects2).map(_.prependPath(FieldName(k, k))) }
        case s @ SCoproduct(info, subtypes, _) =>
          val objects2 = objects + (info -> this)
          s.subtypeInfo(t).flatMap(subtypes.get).map(_.asInstanceOf[Schema[T]].applyValidation(t, objects2)).getOrElse(Nil)
        case SRef(info) => objects.get(info).map(_.asInstanceOf[Schema[T]].applyValidation(t, objects)).getOrElse(Nil)
        case _          => Nil
      })
    } else Nil
  }

  private[tapir] def hasValidation: Boolean = {
    (validator != Validator.pass) || (schemaType match {
      case SOption(element)             => element.hasValidation
      case SArray(element)              => element.hasValidation
      case s: SProduct[T]               => s.fieldsWithValidation.nonEmpty
      case SOpenProduct(_, valueSchema) => valueSchema.hasValidation
      case SCoproduct(_, subtypes, _)   => subtypes.values.exists(_.hasValidation)
      case SRef(_)                      => true
      case _                            => false
    })
  }
}

object Schema extends SchemaExtensions with LowPrioritySchema with SchemaCompanionMacros {
  val ModifyCollectionElements = "each"

  /** Creates a schema for type `T`, where the low-level representation is a `String`. */
  def string[T]: Schema[T] = Schema(SString())

  /** Creates a schema for type `T`, where the low-level representation is binary. */
  def binary[T]: Schema[T] = Schema(SBinary())

  implicit val schemaForString: Schema[String] = Schema(SString())
  implicit val schemaForByte: Schema[Byte] = Schema(SInteger())
  implicit val schemaForShort: Schema[Short] = Schema(SInteger())
  implicit val schemaForInt: Schema[Int] = Schema(SInteger())
  implicit val schemaForLong: Schema[Long] = Schema(SInteger[Long]()).format("int64")
  implicit val schemaForFloat: Schema[Float] = Schema(SNumber[Float]()).format("float")
  implicit val schemaForDouble: Schema[Double] = Schema(SNumber[Double]()).format("double")
  implicit val schemaForBoolean: Schema[Boolean] = Schema(SBoolean())
  implicit val schemaForUnit: Schema[Unit] = Schema(SProduct.empty)
  implicit val schemaForFile: Schema[TapirFile] = Schema(SBinary())
  implicit val schemaForByteArray: Schema[Array[Byte]] = Schema(SBinary())
  implicit val schemaForByteBuffer: Schema[ByteBuffer] = Schema(SBinary())
  implicit val schemaForInputStream: Schema[InputStream] = Schema(SBinary())
  implicit val schemaForInstant: Schema[Instant] = Schema(SDateTime())
  implicit val schemaForZonedDateTime: Schema[ZonedDateTime] = Schema(SDateTime())
  implicit val schemaForOffsetDateTime: Schema[OffsetDateTime] = Schema(SDateTime())
  implicit val schemaForDate: Schema[Date] = Schema(SDateTime())
  implicit val schemaForLocalDateTime: Schema[LocalDateTime] = Schema(SString())
  implicit val schemaForLocalDate: Schema[LocalDate] = Schema(SDate())
  implicit val schemaForZoneOffset: Schema[ZoneOffset] = Schema(SString())
  implicit val schemaForJavaDuration: Schema[Duration] = Schema(SString())
  implicit val schemaForLocalTime: Schema[LocalTime] = Schema(SString())
  implicit val schemaForOffsetTime: Schema[OffsetTime] = Schema(SString())
  implicit val schemaForScalaDuration: Schema[scala.concurrent.duration.Duration] = Schema(SString())
  implicit val schemaForUUID: Schema[UUID] = Schema(SString[UUID]()).format("uuid")
  implicit val schemaForBigDecimal: Schema[BigDecimal] = Schema(SString())
  implicit val schemaForJBigDecimal: Schema[JBigDecimal] = Schema(SString())

  implicit def schemaForOption[T: Schema]: Schema[Option[T]] = implicitly[Schema[T]].asOption
  implicit def schemaForArray[T: Schema]: Schema[Array[T]] = implicitly[Schema[T]].asArray
  implicit def schemaForIterable[T: Schema, C[X] <: Iterable[X]]: Schema[C[T]] = implicitly[Schema[T]].asIterable[C]
  implicit def schemaForPart[T: Schema]: Schema[Part[T]] = implicitly[Schema[T]].map(_ => None)(_.body)
}

trait LowPrioritySchema {
  implicit def derivedSchema[T](implicit derived: Derived[Schema[T]]): Schema[T] = derived.value
}

// annotations

class description(val text: String) extends StaticAnnotation
class encodedExample(val example: Any) extends StaticAnnotation
class default[T](val default: T) extends StaticAnnotation
class format(val format: String) extends StaticAnnotation
class deprecated extends StaticAnnotation
class encodedName(val name: String) extends StaticAnnotation
class validate[T](val v: Validator[T]) extends StaticAnnotation
