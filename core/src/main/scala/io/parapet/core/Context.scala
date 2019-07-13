package io.parapet.core

import java.util.concurrent.atomic.AtomicBoolean

import cats.effect.Concurrent
import cats.effect.concurrent.Deferred
import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import io.parapet.core.Context._
import io.parapet.core.Event.{Envelope, Start}
import io.parapet.core.Scheduler.{Deliver, Task, TaskQueue}

import scala.collection.JavaConverters._

class Context[F[_]](
                     config: Parapet.ParConfig,
                     val taskQueue: TaskQueue[F]) {

  private val processQueueSize = config.schedulerConfig.processQueueSize

  val eventDeliveryHooks: EventDeliveryHooks[F] = new EventDeliveryHooks[F]
  private val processes = new java.util.concurrent.ConcurrentHashMap[ProcessRef, ProcessState[F]]()

  def register(process: Process[F])(implicit ct: Concurrent[F]): F[ProcessRef] = {
    ProcessState(process, processQueueSize).flatMap { s =>
      if (processes.putIfAbsent(process.selfRef, s) != null)
        ct.raiseError(new RuntimeException(s"duplicated process. ref = ${process.selfRef}"))
      else start(process.selfRef).map(_ => process.selfRef)
    }
  }

  private def start(processRef: ProcessRef): F[Unit] = {
    taskQueue.enqueue(Deliver(Envelope(ProcessRef.SystemRef, Start, processRef)))
  }

  def registerAll(processes: List[Process[F]])(implicit ct: Concurrent[F]): F[List[ProcessRef]] = {
    processes.map(register).sequence
  }

  def getProcesses: List[Process[F]] = processes.values().asScala.map(_.process).toList

  def getRunningProcesses: List[Process[F]] =
    processes.values().asScala.filter(!_.stopped).map(_.process).toList

  def getProcess(ref: ProcessRef): Option[Process[F]] = {
    getProcessState(ref).map(_.process)
  }

  def getProcessState(ref: ProcessRef): Option[ProcessState[F]] = {
    Option(processes.get(ref))
  }

  def interrupt(pRef: ProcessRef)(implicit ct: Concurrent[F]): F[Boolean] = {
    getProcessState(pRef) match {
      case Some(s) => s.interrupt
      case None => ct.pure(false)
    }
  }

  def remove(pRef: ProcessRef)(implicit ct: Concurrent[F]): F[Option[Process[F]]] = {
    ct.delay(Option(processes.remove(pRef)).map(_.process))
  }

}

object Context {

  def apply[F[_]:Concurrent](config: Parapet.ParConfig): F[Context[F]] = {
    for {
      taskQueue <- Queue.bounded[F, Task[F]](config.schedulerConfig.queueSize)
    } yield new Context[F](config, taskQueue)
  }

  class ProcessState[F[_] : Concurrent](
                                         queue: TaskQueue[F],
                                         lock: Lock[F],
                                         val process: Process[F],
                                         _interruption : Deferred[F, Unit]) {

    private val ct = implicitly[Concurrent[F]]

    private val _interrupted: AtomicBoolean = new AtomicBoolean(false)
    private val _stopped: AtomicBoolean = new AtomicBoolean(false)
    private val executing: AtomicBoolean = new AtomicBoolean()

    def tryPut(t: Task[F]): F[Boolean] = {
      lock.withPermit(queue.tryEnqueue(t))
    }

    def tryTakeTask: F[Option[Task[F]]] = queue.tryDequeue

    def interrupt: F[Boolean] = {
      ct.delay(_interrupted.compareAndSet(false, true)).flatMap {
        case true => _interruption.complete(()).map(_ => true)
        case false => ct.pure(false)
      }
    }

    def stop(): Boolean = _stopped.compareAndSet(false, true)

    def interruption: F[Unit] = _interruption.get

    def interrupted: Boolean = _interrupted.get()

    def stopped: Boolean = _stopped.get()

    def acquire: F[Boolean] = ct.delay(executing.compareAndSet(false, true))

    def release: F[Boolean] = {
      if (!executing.get())
        ct.raiseError(new RuntimeException("process cannot be released because it wasn't acquired"))
      else {
        // lock required to avoid the situation when worker 1 got suspended during process release,
        // scheduler puts a new task to the process's queue and process ref to processRefQueue
        // worker 2 dequeues process ref and fails to acquire it b/c it's still in executing state
        // thus new task will be lost
        // process must be released before scheduler will add it to processRefQueue
        lock.withPermit {
          queue.isEmpty >>= {
            case true =>
              ct.delay(executing.compareAndSet(true, false)) >>= {
                case false => ct.raiseError(new RuntimeException("concurrent release"))
                case _ => ct.pure(true)
              }
            case false => ct.pure(false) // new task available, don't release yet
          }
        }
      }
    }

  }

  object ProcessState {
    def apply[F[_] : Concurrent](process: Process[F], queueSize: Int): F[ProcessState[F]] =
      for {
        queue <- Queue.bounded[F, Task[F]](queueSize)
        lock <- Lock[F]
        terminated <- Deferred[F, Unit]
      } yield new ProcessState[F](queue, lock, process, terminated)
  }

}
