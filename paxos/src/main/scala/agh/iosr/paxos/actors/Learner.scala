package agh.iosr.paxos.actors

import agh.iosr.paxos.messages.Messages._
import agh.iosr.paxos.predef._
import akka.actor._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.util.Random

object Learner {
  def props(): Props = Props(new Learner)
}

class Learner() extends Actor with ActorLogging {
  var subscribers = new ListBuffer[ActorRef]()
  var memory: mutable.HashMap[String, (InstanceId, Value)] = mutable.HashMap.empty
  var getRequests: mutable.HashMap[Int, (ActorRef, String, ListBuffer[Option[(InstanceId, Value)]])] = mutable.HashMap.empty
  val rand = Random


  var communicator: ActorRef = _

  override def receive: Receive = {
    case Ready =>
      communicator = sender()
      context.become(ready)
      log.info("Learner:" + self + " @ Ready")
  }

  def ready: Receive = {
    case LearnerSubscribe() =>
      subscribers += sender
      log.info("Learner:" + self + " @ Receive")

    case ReceivedMessage(Accepted(RoundIdentifier(instanceId, _), KeyValue(key, value)), _) =>
      log.info("Learner:" + self + " @ Accepted")
      memory.put(key, (instanceId, value))
      subscribers.foreach {_ ! ValueLearned(instanceId, key, value)}


    case KvsGetRequest(key) =>
      log.info("Learner:" + self + " @ KvsGetRequest")
      var requestId = rand.nextInt
      while (getRequests.contains(requestId)) requestId = rand.nextInt

      getRequests += (requestId -> (sender, key, new ListBuffer[Option[(InstanceId, Value)]]))
      communicator ! SendMulticast(LearnerQuestionForValue(requestId, key))

      import scala.concurrent.ExecutionContext.Implicits.global
      context.system.scheduler.scheduleOnce(3 seconds, self, LearnerLoopback(requestId))

    case ReceivedMessage(LearnerQuestionForValue(requestId, key), remoteId) =>
      log.info("Learner:" + self + " @ LearnerQuestionForValue")
      communicator ! SendUnicast(LearnerAnswerWithValue(requestId, memory.get(key)), remoteId)

    case ReceivedMessage(LearnerAnswerWithValue(requestId, value), _) =>
      log.info("Learner:" + self + " @ LearnerAnswerWithValue")
      val reqData = getRequests.get(requestId)
      reqData match {
        case Some(_) => reqData.get._3 += value
        case None => log.info("OVER")
      }

    case LearnerLoopback(requestId) =>
      log.info("Learner:" + self + " @ LearnerLoopback")
      val propsFromMap = getRequests.get(requestId)
      propsFromMap match {
        case Some(props) =>
          var currentMaxInst = -1
          var currentValue = -1
          props._3 foreach {
            case Some((instance, value)) if instance > currentMaxInst =>
              currentMaxInst = instance
              currentValue = value
            case _ =>
          }
          if (currentMaxInst != -1)
            props._1 ! KvsGetResponse(props._2, Option(currentValue))
          else
            props._1 ! KvsGetResponse(props._2, None)
        case None =>
      }
      getRequests.remove(requestId)

    case ReceivedMessage(FallAsleep, _) => context.become(down)

    case _ =>
  }

  def down: Receive = {
    case ReceivedMessage(WakeUp, _) => context.become(ready)

    case _ =>
  }
}
