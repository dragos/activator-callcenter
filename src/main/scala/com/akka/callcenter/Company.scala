package com.akka.callcenter

import java.util.Date
import akka.actor.{ Actor, ActorRef }
import akka.actor.Props
import akka.actor.ActorLogging
import com.typesafe.config.ConfigValue
import com.typesafe.config.ConfigList

object Company {

  case class GiveSkillsForDepartment(deptName: String)
  case class IsDuringBusinessHours(deptName: String, date: Date)
  case object Departments

  def apply() = new Company with StandardDepartmentManager

}

class Company extends Actor with ActorLogging {
  this: DepartmentManager =>

  import Company._

  def receive: Receive = {

    case m @ GiveSkillsForDepartment(deptName: String) => getDepartmentByName(deptName) forward m

    case m @ IsDuringBusinessHours(deptName, date: Date) => getDepartmentByName(deptName) forward m

    case Departments => sender ! departmentNames

  }

}

trait SkillBase {

  val name: String

  val maxPriority: Int

}

case class Skill(val name: String, val maxPriority: Int) extends SkillBase

object Skill {
  type SkillPriority = (Int, SkillBase, ActorRef)
}

trait SkillsProvider {
  this: Actor =>

  def skills: Seq[SkillBase]

}

trait DepartmentBase {

  val name: String

  val businessHours: (Int, Int)
  def isDuringBusiness(date: Date) = businessHours._1 <= date.getHours() && businessHours._2 > date.getHours()
}

object DepartmentBase {

  val generalDept = "general"

  case class Skills(skills: Seq[SkillBase])
  case class DuringBusinessHours(deptName: String, isDuringBusiness: Boolean)
}

trait DepartmentManager {
  this: Actor with ActorLogging =>

  var departmentNames: List[String]
  val departmentMap: Map[String, ActorRef]

  def getDepartmentByName(name: String): ActorRef = departmentMap(name)

  abstract class Department extends DepartmentBase with Actor {
    this: SkillsProvider =>

    import DepartmentBase._
    import Company._

    val businessHours = (9, 18)

    def receive: Receive = {

      case GiveSkillsForDepartment(deptName: String) if name == deptName =>
        sender ! Skills(skills)
      case IsDuringBusinessHours(deptName: String, date: Date) if name == deptName =>
        sender ! DuringBusinessHours(deptName, isDuringBusiness(date))
      case x => log.debug(s"Ooops: Something went wrong: $x")

    }

  }

}

trait StandardDepartmentManager extends DepartmentManager {
  this: Actor with ActorLogging =>

  var departmentNames: List[String] = Nil

  import scala.collection.JavaConverters._
  import ConfigProvider._

  val departmentMap = context.system.settings.config.getList("com.akka.company.departments").foldLeft(
    Map[String, ActorRef]())((res, v) => {
      val dept = v._2.asInstanceOf[ConfigList].asScala
      val dName = dept.head.asInstanceOf[ConfigValue].unwrapped().toString()
      val dSkills = dept(3)
      departmentNames = dName :: departmentNames
      res + (dName -> context.actorOf(Props(new Department() with SkillsProvider {
        val name = dName
        override val businessHours = (dept(1).unwrapped().asInstanceOf[Int],
          dept(2).unwrapped().asInstanceOf[Int])
        val skills = dSkills.foldLeft(List[Skill]())((res1, v1) =>
          Skill(v1._1, v1._2.unwrapped().asInstanceOf[Int]) :: res1)
      }), dName))
    })
}

