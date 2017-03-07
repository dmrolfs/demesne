package contoso

import squants._
import squants.DimensionlessConversions._
import com.wix.accord._
import com.wix.accord.dsl._
import omnibus.commons.identifier._
import contoso.conference._


package object registration {
  //Registration.Contracts/PersonalInfo.cs
  case class PersonalInfo( firstName: String, lastName: String, email: String ) extends Equals {
    override def hashCode: Int = {
      41 * (
        41 * (
          41 + email.toLowerCase.##
        ) + firstName.##
      ) + lastName.##
    }

    override def equals( rhs: Any ): Boolean = rhs match {
      case that: PersonalInfo => {
        if ( this eq that ) true
        else {
          ( that.## == this.## ) &&
          ( that canEqual this ) &&
          ( that.firstName == this.firstName ) &&
          ( that.lastName == this.lastName ) &&
          ( that.email.equalsIgnoreCase( this.email ) )
        }
      }

      case _ => false
    }

    override def canEqual( rhs: Any ): Boolean = rhs.isInstanceOf[PersonalInfo]
  }

  object PersonalInfo {
    implicit val personInfoValidator = validator[PersonalInfo] { p =>
      p.firstName as "first name" is notEmpty
      p.lastName as "last name" is notEmpty
      p.email should matchRegex( """[\w-]+(\.?[\w-])*\@[\w-]+(\.[\w-]+)+""".r ) //DMR: Incorporate email validation into EmailAddress Archetype
    }
  }

  // Conference/Registration/OrderItem.cs
  case class OrderItem( seatTypeId: SeatType.TID, quantity: Dimensionless )

  // Conference/Registration.Contracts/SeatQuantity.cs
  case class SeatQuantity( seatTypeId: SeatType.TID, quantity: Dimensionless ) {
    def count: Int = quantity.toEach.toInt
  }


  //Conference/Registration.Contracts/OrderLine.cs
  trait OrderLine {
    def total: Money
  }


  //Conference/Registration.Contracts/OrderLine.cs
  case class SeatOrderLine(
    seatTypeId: SeatType.TID,
    unitPrice: Price[Dimensionless],
    quantity: Dimensionless
  ) extends OrderLine {
    override val total: Money = unitPrice * quantity
  }
}