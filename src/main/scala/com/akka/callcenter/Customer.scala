package com.akka.callcenter

import akka.actor.Actor
import akka.actor.ActorLogging
import scala.util.Random

object CallNumber {
  val serviceNumber = "(1\\-800\\-SERVICE)"
  val salesNumber = "(1\\-800\\-SALES)"
  val technicNumber = "(1\\-800\\-TECHNIC)"

  val deptNumber = "1\\-800\\-(.*)".r

  val serviceNumberRe = serviceNumber.r
  val salesNumberRe = salesNumber.r
  val technicNumberRe = technicNumber.r
}

trait CallNumber {
  val nbr: String

  import CallNumber._
  import DepartmentBase.generalDept

  def isSalesCallNumber = nbr match {
    case salesNumberRe(number) => true
    case _ => false
  }

  def isServiceCallNumber =
    nbr match {
      case serviceNumberRe(number) => true
      case _ => false
    }

  def isTechnicCallNumber = nbr match {
    case technicNumberRe(number) => true
    case _ => false
  }

  def hasDepartment(deptNames: List[String]) = {
    nbr match {
      case deptNumber(dept) => deptNames.filter(d => d == dept).size > 0
      case _ => false
    }
  }

  def selectDepartment(deptNames: Seq[String]) = {
    nbr match {
      case deptNumber(dept) =>
        deptNames.filter(d => d == dept).headOption.getOrElse(generalDept)
      case _ => generalDept
    }
  }
}

case object FixCallNumber extends CallNumber {

  override val nbr = """1-800-SALES"""
}
case object SalesCallNumber extends CallNumber {

  override val nbr = """1-800-SALES"""
}
case object ServiceCallNumber extends CallNumber {

  override val nbr = """1-800-SERVICE"""
}
case object TechnicCallNumber extends CallNumber {

  override val nbr = """1-800-TECHNIC"""
}

trait CallNumberProvider {

  def makeCallNumber(): CallNumber

}

trait SkillProvider {

  def makeSkill(callNbr: CallNumber): Skill

}

trait RandomCallNumberProvider extends CallNumberProvider {

  val callNumbers = SalesCallNumber :: ServiceCallNumber :: TechnicCallNumber :: Nil

  def makeCallNumber(): CallNumber = callNumbers(Random.nextInt(callNumbers.size))

}

trait RandomSkillProvider extends SkillProvider {
  import CallNumber._

  val marketingSkill = Skill("Marketing", 3)
  val technicSkill = Skill("Technic", 3)
  val generalSkill = Skill("General", 3)

  def makeSkill(callNbr: CallNumber): Skill =
    callNbr match {
      case SalesCallNumber => marketingSkill
      case TechnicCallNumber => technicSkill
      case ServiceCallNumber => generalSkill
    }

}

trait Customer extends Actor with ActorLogging {
  this: CallNumberProvider with SkillProvider =>

  import Customer._

  def receive: Receive = {

    case GetCallNumber =>
      val cn = makeCallNumber
      val nbr = cn.nbr
      val skill = makeSkill(cn)
      log.info(s"GetCallNumber with $nbr")
      sender ! (cn, skill)
  }

}

object Customer {

  case object GetCallNumber
  case class SendCallNumber(number: CallNumber)

  def apply() = new Customer with RandomCallNumberProvider with RandomSkillProvider

}