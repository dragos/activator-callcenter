package com.akka.callcenter

import akka.actor.Actor
import akka.actor.Props
import akka.actor.ActorRef
import akka.actor.ActorLogging
import scala.collection.mutable.ListBuffer
import java.util.Date
import scala.util.Random
import scala.concurrent.duration._
import akka.actor.OneForOneStrategy
import akka.actor.ActorKilledException
import akka.actor.SupervisorStrategy._
import akka.actor.ActorInitializationException
import akka.util.Timeout
import scala.concurrent.Await

trait Routing {
}

object Routing {

  case class Route(callNbr: CallNumber, forSkill: Skill)
  case class RouteAccordingToBusinessHours(agentSuperVisors: Map[String, ActorRef],
    agentSupervisor: (String, ActorRef), duringBusinessHours: Map[String, Boolean], forSkill: Skill)

  case object BusinessHourCheckTimeout

}

class DefaultRouting(callCenter: ActorRef, agentSuperVisors: Map[String, ActorRef], deptNames: Seq[String]) extends Routing with Actor with ActorLogging {
  import Routing._
  import Company.IsDuringBusinessHours
  import DepartmentBase.generalDept

  def receive: Receive = {
    case Route(callNbr: CallNumber, forSkill: Skill) =>
      val originalSender = self
      // Calculate the responsible department

      val department = callNbr.selectDepartment(deptNames)

      val agentSupervisorInfo = (department, agentSuperVisors(department))

      agentSupervisorInfo match {
        //case (_, EscalationSupervisor => // callCenter ! play music for correct service numbers
        case (_, _) =>

          val callDuringBusinessHoursActor = context.actorOf(Props(new Actor with ActorLogging {

            var duringBusinessHours: Map[String, Boolean] = Map()

            def receive = {
              case DepartmentBase.DuringBusinessHours(deptName, isDuringBusiness) =>
                duringBusinessHours += (deptName -> isDuringBusiness)
                businessHourCheckComplete
              case BusinessHourCheckTimeout =>
                sendResponseAndShutdown(BusinessHourCheckTimeout)
            }

            def businessHourCheckComplete() = {
              val duringBusinessHourKeys = duringBusinessHours.keys.toList
              val complete = (agentSuperVisors.keys.size == duringBusinessHourKeys.size) &&
                (true /: agentSuperVisors.keys) { (res, deptName) =>
                  {
                    if (duringBusinessHourKeys.contains(deptName))
                      res && true
                    else
                      res && false
                  }
                }
              if (complete) {
                timeoutMessager.cancel
                sendResponseAndShutdown(RouteAccordingToBusinessHours(agentSuperVisors, agentSupervisorInfo,
                  duringBusinessHours, forSkill))
              }
            }

            def sendResponseAndShutdown(response: Any) = {
              originalSender ! response
              log.debug("Stopping context capturing actor for businessHourCheck")
              context.stop(self)
            }

            import context.dispatcher
            val timeoutMessager = context.system.scheduler.scheduleOnce(250 milliseconds) {
              self ! BusinessHourCheckTimeout
            }
          }))

          val date = new Date()

          agentSuperVisors.keys foreach {
            deptName => callCenter.tell(Company.IsDuringBusinessHours(deptName, date), callDuringBusinessHoursActor)
          }

      }

    case RouteAccordingToBusinessHours(agentSuperVisors: Map[String, ActorRef],
      agentSupervisor: (String, ActorRef), duringBusinessHours: Map[String, Boolean],
      forSkill: Skill) =>

      duringBusinessHours(agentSupervisor._1) match {

        case true => agentSupervisor._2 ! RoutingStrategyProvider.Route(forSkill)
        case false => agentSuperVisors.filter(supervisor => duringBusinessHours(supervisor._1)).headOption.map(_._2).getOrElse(agentSuperVisors(generalDept)) ! RoutingStrategyProvider.Route(forSkill)
      }
  }

}

trait RoutingProvider {

  val routing: String

  def makeRouting(callCenter: ActorRef, agentSuperVisors: Map[String, ActorRef], departmentNames: Seq[String])
}

trait DefaultRoutingProvider extends RoutingProvider {
  this: Actor =>

  val routing: String = "DefaultRoutingProvider"

  def makeRouting(callCenter: ActorRef,
    agentSuperVisors: Map[String, ActorRef], deptNames: Seq[String]) =
    context.actorOf(Props(new DefaultRouting(callCenter, agentSuperVisors: Map[String, ActorRef], deptNames)), routing)

}

trait AgentSupervisorProvider {
  this: Actor =>

  var agentSuperVisors: Map[String, ActorRef]

  def makeAgentSupervisor(callCenter: ActorRef, department: ActorRef) =
    context.actorOf(Props(AgentSupervisor(callCenter, department)), s"AgentSupervisor${department.path.name.split("/").last.capitalize}")

}

object CallCenter {

  case class CustomerCall(callNbr: CallNumber, forSkill: Skill)

  def apply(company: ActorRef) = {
    new CallCenter(company) with AgentSupervisorProvider with DefaultRoutingProvider
  }

}

class CallCenter(company: ActorRef) extends Actor with ActorLogging {
  this: AgentSupervisorProvider with RoutingProvider =>

  import CallCenter._
  import Routing.Route
  import akka.pattern.{ ask, pipe }
  import Company._

  implicit val timeout = Timeout(4.seconds)

  var agentSuperVisors: Map[String, ActorRef] = null

  def departmentLocalName(dept: ActorRef) = dept.path.name.split("/").last

  override def preStart = {
    val deptNames = Await.result((company ? Departments).mapTo[Seq[String]], 5.seconds)
    val departments = (List[ActorRef]() /: deptNames) {
      (res, dept) =>
        {
          (context.actorFor("akka://CallCenterSimulation/user/Company" + "/" + dept)) :: res
        }
    }
    agentSuperVisors = (Map[String, ActorRef]() /: departments) {
      (res, dept) => res + (departmentLocalName(dept) -> makeAgentSupervisor(self, dept))
    }
    makeRouting(self, agentSuperVisors, deptNames)
  }
  
  override val supervisorStrategy = OneForOneStrategy() {
    case _: ActorInitializationException => Stop
    case _: ActorKilledException => Restart
    case _: Exception => Resume
    case _ => Resume
  }

  def receive: Receive = {

    case m @ Company.GiveSkillsForDepartment(deptName) => company forward m

    case m @ Company.IsDuringBusinessHours(deptName, date) => company forward m

    case CustomerCall(callNbr: CallNumber, forSkill: Skill) =>
      // The routingStrategyProvider is an actor which routes the call according to the rules for each department
      log.info(s"Route call number $callNbr")
      context.actorFor(routing) ! Route(callNbr, forSkill)
  }

}