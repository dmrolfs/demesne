package demesne.serialization.kryo

import com.esotericsoftware.kryo.Kryo
import com.romix.scala.serialization.kryo.{
  EnumerationSerializer,
  ScalaCollectionSerializer,
  ScalaImmutableMapSerializer,
  ScalaImmutableSetSerializer
}
import org.joda.{ time => joda }
import de.javakaffee.kryoserializers.jodatime.{
  JodaDateTimeSerializer,
  JodaLocalDateSerializer,
  JodaLocalDateTimeSerializer
}

/** Created by rolfsd on 11/1/16.
  */
class KryoSerializationInit {

  def customize( kryo: Kryo ): Unit = {
    // Serialization of Scala enumerations
    kryo.addDefaultSerializer( classOf[scala.Enumeration#Value], classOf[EnumerationSerializer] )
    kryo.register( Class.forName( "scala.Enumeration$Val" ) )
    kryo.register( classOf[scala.Enumeration#Value] )

    // Serialization of Scala maps like Trees, etc
    kryo.addDefaultSerializer(
      classOf[scala.collection.immutable.Map[_, _]],
      classOf[ScalaImmutableMapSerializer]
    )
    kryo.addDefaultSerializer(
      classOf[scala.collection.generic.MapFactory[scala.collection.immutable.Map]],
      classOf[ScalaImmutableMapSerializer]
    )

    // Serialization of Scala sets
    kryo.addDefaultSerializer(
      classOf[scala.collection.immutable.Set[_]],
      classOf[ScalaImmutableSetSerializer]
    )
    kryo.addDefaultSerializer(
      classOf[scala.collection.generic.SetFactory[scala.collection.immutable.Set]],
      classOf[ScalaImmutableSetSerializer]
    )

    // Serialization of all Traversable Scala collections like Lists, Vectors, etc
    kryo.addDefaultSerializer(
      classOf[scala.collection.Traversable[_]],
      classOf[ScalaCollectionSerializer]
    )

    kryo.addDefaultSerializer( classOf[joda.DateTime], classOf[JodaDateTimeSerializer] )
    kryo.addDefaultSerializer( classOf[joda.LocalDate], classOf[JodaLocalDateSerializer] )
    kryo.addDefaultSerializer( classOf[joda.LocalDateTime], classOf[JodaLocalDateTimeSerializer] )

    kryo.addDefaultSerializer( classOf[com.typesafe.config.Config], classOf[KryoConfigSerializer] )
    kryo.addDefaultSerializer(
      Class.forName( "com.typesafe.config.impl.SimpleConfig" ),
      classOf[KryoConfigSerializer]
    )
  }
}
