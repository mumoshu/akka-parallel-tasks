package org.example

import akka.actor._
import akka.agent.Agent
import akka.routing._
import akka.util.Timeout
import org.example.TaskFinished
import org.example.EnqueueTasks
import org.example.Status
import org.example.Task

// Needed for `?` method
import akka.pattern.ask
import akka.transactor._
import concurrent.duration._
import concurrent.{Await, Future}
import concurrent.ExecutionContext.Implicits.global
import concurrent.stm._

case class SimulatedExecutorException(message: String) extends RuntimeException(message)

/** The executor executes received tasks
  */
class Executor extends Actor with ActorLogging {
  def receive = {
    case task : Task =>
      val processingTime = (math.random * 10000).toInt
      println("Executing task: " + task + ", should take " + processingTime + "ms")
      Thread.sleep(processingTime)
      sender ! TaskFinished(task)
    case coordinated @ Coordinated(task: Task) =>
      coordinated.atomic { implicit txn =>
        val processingTime = (math.random * 10000).toInt
        log.debug("Executing task: " + task + ", should take " + processingTime + "ms")
        if (math.random < 0.5) {
          throw new SimulatedExecutorException("Error just before executing task: " + task)
        }
        Txn.afterCommit { txnStatus =>
          log.debug("Successfully started executing task")
          Thread.sleep(processingTime)
          sender ! TaskFinished(task)
        }
      }
  }
}

/** the coordinator's status
  */
case class Status(
  remainingTasks: List[Task],
  numRunningExecutors: Int,
  waitingClients: List[ActorRef]
)

/** The task executed by an executor
  * @param n An input value. We don't do anything on it in this example, but the real task should compute the result from the input.
  */
case class Task(uuid: String = java.util.UUID.randomUUID().toString, n: Int)

trait Message
case object TryDequeueingTask extends Message
case class EnqueueTasks(tasks: List[Task]) extends Message
case class TaskFinished(task: Task) extends Message

trait ClientMessage
case object AllTasksFinished extends ClientMessage

/** The coordinator splits a set of tasks and passes each task to the available executor for parallel execution.
  */
class Coordinator(nrOfExecutors: Int) extends Actor with ActorLogging {

  val router = context.actorOf(Props[Executor].withRouter(SmallestMailboxRouter(nrOfInstances = nrOfExecutors)), name = "executorRouter")

  // All the processing of received messages are coordinated by this agent.
  val status: Agent[Status] = Agent(Status(
    remainingTasks = List.empty,
    numRunningExecutors = 0,
    waitingClients = List.empty
  ))(context.system)

  def receive = {
    case coordinated @ Coordinated(TryDequeueingTask) =>
      status.send { status =>
        if (status.numRunningExecutors < nrOfExecutors) {
          status.remainingTasks match {
            case head :: tail =>
              log.debug("Reliably change the job status from `pending` to `queueing`: " + head)
              coordinated.atomic { implicit txn =>
                Txn.afterCommit { txnStatus =>
                  log.debug("Reliably change the job status from `queueing` to `running`: " + head)
                }
                Txn.afterRollback { txnStatus =>
                  log.debug("Reliably rolls back the change to the job status: " + head)

                  log.debug("Retrying...")
                  implicit val timeout = Timeout(10 seconds)
                  self ! Coordinated(TryDequeueingTask)
                }
                Txn.afterCompletion { txnStatus =>
                  log.debug("Completed task(it may be either finished or failed): " + head)
                }
                router ! coordinated.coordinate(head)
                implicit val timeout = Timeout(10 seconds)
                self ! Coordinated(TryDequeueingTask)
                status.copy(
                  remainingTasks = status.remainingTasks.filterNot(head ==),
                  numRunningExecutors = status.numRunningExecutors + 1
                )
              }
            case Nil =>
              status
          }
        } else {
          status
        }
      }
    case TryDequeueingTask =>
      status.send { status =>
        println("TryDequeueingTask")
        if (status.numRunningExecutors < nrOfExecutors) {
          status.remainingTasks match {
            case head :: tail =>
              router ! head
              self ! TryDequeueingTask
              status.copy(
                // TODO Should we just mark the task `running` here, and remove it after it is finished?
                remainingTasks = status.remainingTasks.filterNot(head ==),
                numRunningExecutors = status.numRunningExecutors + 1
              )
            case Nil =>
              println("Nothing to run: status=" + status)
              status
          }
        } else {
             println("All the executors are busy. Waiting for any executor to finish.")
             status
        }
      }
    case EnqueueTasks(tasks) =>
      val replyTo = sender
      status.send { status =>
        // Tips:
        // `sender` is not avaiable inside `send` block!
        // You must preserve the sender before the call to `send`, e.g. `val replyTo = sender; status.send { ....`

        println("EnqueueTasks: " + tasks.size + " tasks")
        println("replyTo: " + replyTo)
        implicit val timeout = Timeout(10 seconds)
        self ! Coordinated(TryDequeueingTask)
        status.copy(
          remainingTasks = status.remainingTasks ++ tasks,
          waitingClients = status.waitingClients :+ replyTo
        )
      }
    case TaskFinished(task) =>
      status.send { status =>
        println("TaskFinished: " + task)
        // Now, there is atleast one executor avaiable to run a task.
        if (status.remainingTasks.size > 0) {
          self ! TryDequeueingTask
          status.copy(
            numRunningExecutors = status.numRunningExecutors - 1
          )
        } else {
          status.numRunningExecutors match {
            case 1 =>
              println("All the tasks are finished: Notifying to clients: " + status.waitingClients)
              status.waitingClients.map(_ ! akka.actor.Status.Success(AllTasksFinished))
              status.copy(
                numRunningExecutors = status.numRunningExecutors - 1,
                waitingClients = List.empty
              )
            case x if x > 1 =>
              status.copy(
                numRunningExecutors = status.numRunningExecutors - 1
              )
          }
        }
     }
  }
}

object TestEnv {
  val system = ActorSystem()
  implicit val timeout = Timeout(180 seconds)
  // TODO Set proper dispatcher for your own use case
  //implicit val executionContext = system.dispatcher
  //import system.dispatcher

  val master = system.actorOf(Props(new Coordinator(2)))
  val n = 10

  val msg = EnqueueTasks((1 to n).map(x => Task(n = x)).toList)

  val future: Future[ClientMessage] = (master ? msg).mapTo[ClientMessage]
  future.onSuccess {
    case AllTasksFinished =>
      println("onSuccess called with `AllTasksFinished`")
  }
  future.onFailure {
     case failure =>
       println("onFailure called with: " + failure)
  }

  val result = Await.result(future, timeout.duration)

  require(result == AllTasksFinished)

  // You need to shutdown the actor system to properly shutdown JVM
  println("Shutting down the ActorSystem")
  system.shutdown()
}
