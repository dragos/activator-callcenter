package com.akka.callcenter

import akka.actor.Actor
import akka.actor.Props
import akka.actor.ActorRef
import akka.actor.ActorLogging
import scala.collection.mutable.ListBuffer
import java.util.Date
import scala.util.Random
import scala.concurrent.duration._
import akka.actor.Cancellable

trait AgentResponsiveness {
  val maxResponseTimeMS: Int
  def responseDuration = scala.util.Random.nextInt(maxResponseTimeMS).millis
}

class Agent(val callCenter: ActorRef = null, val name: String = "") extends Actor with ActorLogging {
  this: AgentResponsiveness =>

  import Agent._
  import Skill._

  implicit val ec = context.dispatcher
  var pendingDelivery: Option[Cancellable] = None

  def scheduleHandleCallforSkill(skill: Skill, resultReceiver: ActorRef): Cancellable = {
    context.system.scheduler.scheduleOnce(responseDuration, self,
      HandleCall(skill, resultReceiver))
  }

  def handleSpecificTask: Receive = {
    case HandleSpecificTask => log.debug("handleSpecificTask")
  }

  def takeCall: Receive = {

    case TakeCall(forSkill: Skill, skillHandlers: List[SkillPriority]) =>
      log.debug(s"TakeCall for skill $forSkill")
      pendingDelivery = Some(scheduleHandleCallforSkill(forSkill, sender))
      context.become(handleSpecificTask orElse workOnCall)

    case Busy_? => sender ! false

  }

  def workOnCall: Receive = {
    case HandleCall(forSkill: Skill, resultReceiver: ActorRef) =>
      log.debug(s"HandleCall for skill $forSkill by ${this.name} for sender $resultReceiver")
      pendingDelivery = None
      resultReceiver ! CallHandled(forSkill)
      context.become(handleSpecificTask orElse takeCall)
    case TakeCall(forSkill: Skill, skillPriorities: List[SkillPriority]) =>
      sender ! CallNotHandled(self, forSkill, skillPriorities)
    case Busy_? => sender ! true
  }

  def receive = handleSpecificTask orElse takeCall

}

class TelephoneAnswerAgent extends Actor with ActorLogging {
  this: AgentResponsiveness =>

  import Agent._
  import RoutingStrategyProvider.Route
  import Skill._

  implicit val ec = context.dispatcher

  def takeCall: Receive = {

    case TakeCall(forSkill: Skill, skillHandlers: List[SkillPriority]) =>
      log.debug(s"TelephoneAnswer.TakeCall for skill $forSkill")
      context.system.scheduler.scheduleOnce(responseDuration, sender, 
                                            Route(forSkill))
  }

  override def receive = takeCall

}

object Agent {

  import Skill._

  val nullAgent: Agent = AgentProvider.makeAgent(Actor.noSender, "")

  case class TakeCall(forSkill: Skill, skillHandlers: List[SkillPriority])
  case class HandleCall(forSkill: Skill, sender: ActorRef)
  case class CallHandled(forSkill: Skill)
  case class CallNotHandled(agent: ActorRef, forSkill: Skill, skillHandlers: List[SkillPriority])
  case object Busy_?
  case object HandleSpecificTask

}

trait AgentProvider {

  var agents: Option[Seq[ActorRef]] = None

  def makeAgent(callCenter: ActorRef, name: String) = new Agent(callCenter, name) with AgentResponsiveness {
    //val maxResponseTimeMS = 300000
    val maxResponseTimeMS = 1000
  }

  def makeTelephoneAnswerAgent = new TelephoneAnswerAgent() with AgentResponsiveness {
    val maxResponseTimeMS = 1000
  }

}

object AgentProvider extends AgentProvider
