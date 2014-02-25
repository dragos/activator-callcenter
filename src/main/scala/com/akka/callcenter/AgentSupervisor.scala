package com.akka.callcenter

import akka.actor.{ ActorRef, Actor, Props, ActorKilledException, ActorInitializationException, ActorLogging }
import scala.concurrent.duration._
import akka.actor.SupervisorStrategy._
import akka.actor.{ OneForOneStrategy }
import akka.routing.BroadcastRouter
import com.typesafe.config.ConfigList
import akka.util.Timeout
import akka.pattern.{ ask, pipe }
import akka.actor.ActorSystem
import scala.concurrent.Await
import scala.util.Success
import scala.concurrent.Future
import scala.util.Random
import akka.actor.ActorRef
import akka.actor.ActorLogging
import com.typesafe.config.ConfigList

trait SkillSet {

  import Skill._

  val name: String

  val agentSkills: Map[ActorRef, Seq[SkillPriority]]

}

trait SkillSetProvider {

  var skillSet: Option[SkillSet] = None

  def makeSkillSet(agents: Seq[ActorRef], deptSkills: Seq[SkillBase]): SkillSet
}

trait RandomSkillSetProvider extends SkillSetProvider {
  this: AgentSupervisor with ActorLogging =>

  import Skill._

  def makeSkillRandomPriority(skill: SkillBase, agent: ActorRef) = (Random.nextInt(skill.maxPriority), skill, agent)
  def makeSkillsRandomPriority(skills: Seq[SkillBase], agent: ActorRef) = (0 to Random.nextInt(skills.size)).map(i =>
    (Random.nextInt(skills(i).maxPriority), skills(i), agent))

  def makeSkillSet(agents: Seq[ActorRef], deptSkills: Seq[SkillBase]): SkillSet = {
    (new SkillSet {
      val name = departmentName
      val agentSkills = (Map[ActorRef, Seq[SkillPriority]]() /: (0 to deptSkills.size))((res1, skillIdx) => (for {
        agent <- agents
      } yield (agent -> makeSkillsRandomPriority(deptSkills, agent))).toMap ++ res1)
    })
  }
}

trait ConfigSkillSetProvider extends SkillSetProvider {
  this: AgentSupervisor with ActorLogging =>

  import Skill._
  import ConfigProvider._

  def makeSkillSet(agents: Seq[ActorRef], deptSkills: Seq[SkillBase]): SkillSet = {
    import scala.collection.JavaConverters._
    val deptAgents = context.system.settings.config.getList("com.akka.agents." + departmentName)
    val actorSkillMap = deptAgents.foldLeft(Map[ActorRef, Seq[SkillPriority]]())((res, v) => {
      val actorSkills = v._2.asInstanceOf[ConfigList].asScala
      val agentName = "akka://CallCenterSimulation/user/CallCenter/" +
        self.path.name + "/AgentSupervisor/" + actorSkills(0).unwrapped().toString()
      val agentActor = context.actorFor(agentName)
      val skills = actorSkills(1).foldLeft(List[SkillPriority]())((res1, v1) => {
        val prio = v1._2.unwrapped().asInstanceOf[Int]
        (prio, Skill(v1._1, prio), agentActor) :: res1
      })
      res + (agentActor -> skills)
    })
    new SkillSet {
      val name = departmentName
      val agentSkills = actorSkillMap
    }
  }
}

trait RoutingStrategyProvider {
  this: Actor =>

  def route(): Receive

}

object RoutingStrategyProvider {
  case class Route(forSkill: Skill)
}

trait SkillBasedBusyAskRoutingStrategyProvider extends RoutingStrategyProvider {
  this: AgentSupervisor with SkillSetProvider =>

  import RoutingStrategyProvider._
  import Agent._
  import Skill._

  implicit val timeout = Timeout(4.seconds)
  import scala.concurrent.ExecutionContext.Implicits.global

  def calculateAgentForSkill(skill: Skill, skillPriorities: Option[List[SkillPriority]] = None, withoutAgent: ActorRef = Actor.noSender) = {
    val agentBusyFutureList = skillSet.map(sk => sk.agentSkills.keys.flatMap(
      k => sk.agentSkills(k)).toList.map(sk => {
        (sk._3 ? Agent.Busy_?).mapTo[Boolean] map { busy => (sk, !busy) }
      })).getOrElse(List())
    val agentBusyFuture = Future.sequence(agentBusyFutureList)
    val priorities = Await.result(agentBusyFuture.map(agentBusy => (List[SkillPriority]() /: agentBusy)((res, ag) =>
      if (ag._2 && ag._1._2.name == skill.name) ag._1 :: res else res).sortBy(sk => sk._1)), 1 seconds).asInstanceOf[List[SkillPriority]]
    val agent = priorities.headOption.map(a => a._3).getOrElse(telephoneAnswer)
    (priorities, agent)
  }

  def route(): Receive = {

    case Route(forSkill: Skill) =>
      val (skillPriorities, agent) = calculateAgentForSkill(forSkill)
      log.debug(s"Route found agent $agent")
      agent.tell(TakeCall(forSkill, skillPriorities), self)
    case CallHandled(forSkill: Skill) => log.debug(s"Call handled for skill $forSkill")

  }

}

trait SkillBasedBusyReRoutingStrategyProvider extends RoutingStrategyProvider {
  this: AgentSupervisor with SkillSetProvider =>

  import RoutingStrategyProvider._
  import Agent._
  import Skill._

  def calculateAgentForSkill(skill: Skill, skillPriorities: Option[List[SkillPriority]] = None) = {
    val priorities = skillPriorities.getOrElse(skillSet.map(sk => sk.agentSkills.keys.flatMap(k => sk.agentSkills(k)).toList.filter(sk => sk._2.name == skill.name).sortBy(sk => sk._1)).getOrElse(List()))
    val agent = priorities.headOption.map(a => a._3).getOrElse(telephoneAnswer)
    (priorities, agent)
  }
  def recalculateAgentForSkill(skillPriorities: List[SkillPriority], withoutAgent: ActorRef) = skillPriorities.filter(sk => sk._3 != withoutAgent).sortBy(sk => sk._1).headOption.map(a => a._3).getOrElse(telephoneAnswer)

  def route(): Receive = {

    case Route(forSkill: Skill) =>
      val (skillPriorities, agent) = calculateAgentForSkill(forSkill)
      agent.tell(TakeCall(forSkill, skillPriorities), self)
    case CallHandled(forSkill: Skill) => log.debug(s"Call handled for skill $forSkill")
    case CallNotHandled(handlingAgent: ActorRef, forSkill: Skill, skillPriorities: List[SkillPriority]) =>
      // Take the next agent and send call again
      val agent = recalculateAgentForSkill(skillPriorities, handlingAgent)
      agent.tell(TakeCall(forSkill, skillPriorities), self)

  }
}

object AgentSupervisor {

  case object GetAgentBroadcaster
  case class AgentBroadcaster(broadcaster: ActorRef)

  case class GiveSkillSetsForAgents
  case class SkillsForDepartment(skills: Seq[SkillBase])

  def apply(callCenter: ActorRef, department: ActorRef) = new AgentSupervisor(callCenter, department) with SkillBasedBusyAskRoutingStrategyProvider //with RandomSkillSetProvider
  with ConfigSkillSetProvider with AgentProvider
}

class AgentSupervisor(val callCenter: ActorRef, val department: ActorRef) extends Actor with ActorLogging { this: RoutingStrategyProvider with SkillSetProvider with AgentProvider =>
  import AgentSupervisor._
  import SkillsForDepartmentHandler._
  import Agent._
  import ConfigProvider._

  val departmentName = department.path.name.split("/").last
  val telephoneAnswerName = s"TelephoneAnswerFor${departmentName.capitalize}Handler"

  def telephoneAnswer = context.actorFor(telephoneAnswerName)

  override val supervisorStrategy = OneForOneStrategy() {
    case _: ActorKilledException => Restart
    case _: ActorInitializationException => Escalate
    case _ => Resume
  }

  case class GetChildren(forSomeone: ActorRef)
  case class Children(children: Iterable[ActorRef], childrenFor: ActorRef)

  def askSkillsFromDepartment = {
    val originalSender = self
    val handler = context.actorOf(Props(SkillsForDepartmentHandler.makeHandler(originalSender, department)), s"SkillsFor${departmentName.capitalize}Handler")
    callCenter.tell(Company.GiveSkillsForDepartment(departmentName), handler)
  }
  
  override def preStart() {
    context.actorOf(Props(new Actor with ActorLogging {
      val config = context.system.settings.config
       override val supervisorStrategy = OneForOneStrategy() {
        case _: ActorKilledException => Restart
        case _: ActorInitializationException => Stop
        case _ => Resume
      }
      
      override def preStart() {
        import scala.collection.JavaConverters._

        val deptAgents = context.system.settings.config.getList("com.akka.agents." + departmentName)
        agents = Some(deptAgents.foldLeft(List[ActorRef]())((res, v) => {
          val agentName = v._2.asInstanceOf[ConfigList].asScala.head.unwrapped().toString()
          context.actorOf(Props(makeAgent(callCenter, agentName)), agentName) :: res
        }))
        askSkillsFromDepartment
      }
      def receive = {
        case GetChildren =>
          log.info(s"${self.path.name} received GetChildren from ${sender.path.name}")
          sender ! context.children.toSeq
      }
    }), "AgentSupervisor")

    context.actorOf(Props(makeTelephoneAnswerAgent), telephoneAnswerName)
  }

  implicit val childrenFutureTimeout = Timeout(4.seconds)

  def noRouter: Receive = {
    case SkillsForDepartment(skills) =>
      skillSet = Some(makeSkillSet(agents.get, skills))
      log.debug(s"SkillSets: ${
        skillSet.map {
          set => set.name + ((": ") /: set.agentSkills.keys) { (res1, x) => res1 + " " + x + "-> " + set.agentSkills(x) }
        }
      }")
    case SkillsForDepartmentTimeout =>
      log.debug("SkillsForDepartmentTimeout received. Implement retry for #number of times then generate ActorInitializationException (should be escalated according to supervisorStrategy)")
    case GetAgentBroadcaster =>
      import scala.concurrent.ExecutionContext.Implicits.global

      log.info("AgentSupervisor received GetAgentBroadcaster")
      val destinedFor = sender
      val actor = context.actorFor("AgentSupervisor")
      (actor ? GetChildren).mapTo[Seq[ActorRef]].map {
        agents =>
          (Props().withRouter(BroadcastRouter(agents.toList)),
            destinedFor)
      } pipeTo self
    case (props: Props, destinedFor: ActorRef) =>
      log.info(s"AgentSupervisor received (${props.toString()},${destinedFor.toString()}) (transforming to withRouter)")
      val router = context.actorOf(props, "Agents")
      destinedFor ! AgentBroadcaster(router)
      context.become(withRouter(router) orElse route)
  }

  def withRouter(router: ActorRef): Receive = {
    case GetAgentBroadcaster =>
      sender ! AgentBroadcaster(router)
    case Children(_, destinedFor) =>
      destinedFor ! AgentBroadcaster(router)
  }

  def receive = noRouter orElse route
}

object SkillsForDepartmentHandler {

  case object SkillsForDepartmentTimeout
  case class DepartmentSkills()

  def makeHandler(sender: ActorRef, department: ActorRef) = new SkillsForDepartmentHandler(sender, department)

}

class SkillsForDepartmentHandler(originalSender: ActorRef, department: ActorRef) extends Actor with ActorLogging {

  import SkillsForDepartmentHandler._
  import DepartmentBase._
  import AgentSupervisor._

  def receive: Receive = {
    case Skills(skills) =>
      log.debug(s"Skills received for department ${department.toString}")
      checkDepartmentSkillsComplete(skills)
    case _ => sendResponseAndShutdown(SkillsForDepartmentTimeout)
  }

  def checkDepartmentSkillsComplete(skills: Seq[SkillBase]) = {
    log.debug(s"Values received for department $department")
    timeoutMessager.cancel
    sendResponseAndShutdown(SkillsForDepartment(skills))
  }

  def sendResponseAndShutdown(response: Any) = {
    log.debug(s"Sending response to sender ${originalSender.toString}")
    originalSender ! response
    log.debug("Stopping context capturing actor")
    context.stop(self)
  }

  import context.dispatcher
  val timeoutMessager = context.system.scheduler.scheduleOnce(250 milliseconds) {
    self ! SkillsForDepartmentTimeout
  }

}
