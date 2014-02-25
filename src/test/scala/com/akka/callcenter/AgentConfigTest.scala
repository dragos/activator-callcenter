package com.akka.callcenter

import scala.util.Random
import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers
import akka.actor.{ ActorSystem, Actor, Props, ActorRef }
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigList
import com.typesafe.config.impl.SimpleConfigObject
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValue
import com.typesafe.config.ConfigMergeable
import org.scalatest.matchers.Matchers
import org.scalatest.matchers.ShouldMatchers
import scala.concurrent.duration._

class AgentConfigTest extends TestKit(ActorSystem()) with ImplicitSender with WordSpec with ShouldMatchers with BeforeAndAfterAll {
  import akka.event.Logging.Info
  import akka.testkit.TestProbe
  import Customer._
  import ConfigProvider._
  import Skill._
  
  override def afterAll: Unit = {
    system.shutdown()
    system.awaitTermination(10.seconds)
  }

  "Agent" should {
    "should be configurable" in {

      val agentsSalesCfg = ConfigFactory.parseString("" +
        "com { " +
        "	akka { " +
        "		agents { SALES = " +
        "	[[SalesAgent1, {marketing = 1, marketing1 = 3}], " +
        "    [SalesAgent2, {marketing = 3, marketing1 = 1}], " +
        "    [SalesAgent3, {marketing = 2, marketing1 = 2}], " +
        "    [SalesAgent4, {marketing = 3, marketing1 = 3}]," +
        "    [SalesAgent5, {marketing = 2, marketing1 = 2}]]}}}")
      val agentsGeneral = ConfigFactory.parseString("com { akka { agents { GENERAL = " +
        "    [[GeneralAgent1, {general = 1, general1 = 2}]," +
        "    [GeneralAgent2, {general = 1, general1 = 1}]," +
        "    [GeneralAgent3, {general = 1, general1 = 3}]," +
        "    [GeneralAgent4, {general = 3, general1 = 1}]," +
        "    [GeneralAgent5, {general = 1, general1 = 1}]," +
        "    [GeneralAgent6, {general = 2, general1 = 2}]]}}}")
      val agentsTechnic = ConfigFactory.parseString("com { akka { agents { TECHNIC = " +
        "    [[TechnicAgent1, {technic = 1, technic1 = 1}]," +
        "    [TechnicAgent2, {technic = 3, technic1 = 1}]," +
        "    [TechnicAgent3, {technic = 1, technic1 = 2}]," +
        "    [TechnicAgent4, {technic = 2, technic1 = 2}]," +
        "    [TechnicAgent5, {technic = 3, technic1 = 3}]" +
        "]}}}")

      val dept = "SALES"

      val agentsSales = agentsSalesCfg.getList("com.akka.agents." + dept)

      val actorSkillMap = agentsSales.foldLeft(Map[String, Seq[SkillPriority]]())((res, v) => {
        val cf = v._2
        val actorSkills = cf.asInstanceOf[ConfigList]
        println(s"skills: $actorSkills")
        val skills = actorSkills.get(1).foldLeft(List[SkillPriority]())((res1, v1) => {
          val prio = v1._2.unwrapped().asInstanceOf[Int]
          (prio, Skill(v1._1, prio), Actor.noSender) :: res1
        })
        val actorName = actorSkills.get(0).unwrapped().toString() // replace with ActorRef
        res + (actorName -> skills)
      })
      actorSkillMap.size should be > 0
      actorSkillMap.keys should contain("SalesAgent1")
    }
  }
}

