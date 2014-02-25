package com.akka.callcenter

import akka.actor.{ Props, ActorRef, ActorSystem }
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.actor.ActorLogging
import akka.actor.Actor
import Customer.{ GetCallNumber, SendCallNumber }

object Main {

  implicit val timeout = Timeout(5.seconds)
  val system = ActorSystem("CallCenterSimulation")
  val company = system.actorOf(Props(Company()), "Company")
  val customer = system.actorOf(Props((Customer())), "Customer")
  val callCenter = system.actorOf(Props(CallCenter(company)), "CallCenter")

  def main(args: Array[String]) {

    Range(1, 20).foreach(_ => {
      val (callNumber, skill) = Await.result((customer ? Customer.GetCallNumber).mapTo[(CallNumber, Skill)], 5.seconds)

      system.scheduler.scheduleOnce(200.millis) {
        callCenter ! CallCenter.CustomerCall(callNumber,
          skill)
      }
    })
    system.scheduler.scheduleOnce(300.seconds) { system.shutdown() }
  }

}
