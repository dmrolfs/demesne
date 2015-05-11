package contoso

import com.github.nscala_time.time.{ Imports => joda }
import com.github.nscala_time.time.Imports._
import squants._
import squants.DimensionlessConversions._
import squants.market.USD
import com.wix.accord._
import com.wix.accord.dsl._
import peds.commons.identifier._


package object conference {
  // Conference/Conference/SeatType.cs
  case class SeatType(
    id: SeatType.TID,
    name: String,
    description: String,
    quantity: Dimensionless,
    price: Price[Dimensionless]
  ) extends Equals {
    override def hashCode: Int = 41 * ( 41 + id.## )

    override def equals( rhs: Any ): Boolean = rhs match {
      case that: SeatType => {
        if ( this eq that ) true
        else {
          ( that.## == this.## ) &&
          ( that canEqual this ) &&
          ( that.id == this.id )
        }
      }

      case _ => false
    }

    override def canEqual( rhs: Any ): Boolean = rhs.isInstanceOf[SeatType]
  }

  object SeatType {
    type ID = ShortUUID
    type TID = TaggedID[ID]

    implicit val seatTypeValidator = validator[SeatType] { st =>
      st.name is notEmpty
      st.name.size as "name length" is within( 2 to 70 )
      st.description is notEmpty
      st.description has size <= 250
      st.quantity is between( Each( 0 ), Each( 100000 ) )
      (st.price * Each(1)) as "seat type price" is between( USD(0), USD(50000) ) // squants doesn't impl Ordering[Price[Dimensionless]] so need to convert to Money
    }
  }


  // Conference/Conference/ConferenceInfo.cs
  case class ConferenceInfo(
    id: ConferenceModule.ID,
    name: String,
    slug: String,
    ownerName: String,
    ownerEmail: String, //DMR: EmailAddress Archetype
    scheduled: joda.Interval,
    seats: Set[SeatType] = Set(),
    description: Option[String] = None,
    location: Option[String],  //DMR: Geolocation Archetype
    tagline: Option[String] = None,
    accessCode: Option[String] = None,
    wasEverPublished: Boolean = false,
    twitterSearch: Option[String] = None
  )

  object ConferenceInfo {
    implicit val conferenceInfoValidator = validator[ConferenceInfo] { c =>
      c.name is notEmpty
      c.slug is notEmpty
      c.slug should matchRegex( """^\w+$""".r )
      c.accessCode has size >= 6
      c.ownerName as "owner's name" is notEmpty
      c.ownerEmail as "owner's email" should matchRegex( """[\w-]+(\.?[\w-])*\@[\w-]+(\.[\w-]+)+""".r ) //DMR: Incorporate email validation into EmailAddress Archetype
    }
  }


  // Conference/Registration/ReadModel/ConferenceAlias.cs
  case class ConferenceAlias( id: ConferenceModule.ID, code: String, name: String, tagline: String )


  trait ContosoError
}
