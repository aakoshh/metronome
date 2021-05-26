package io.iohk.metronome.examples.robot.app

import cats.implicits._
import cats.effect.{Blocker, Resource}
import cats.effect.concurrent.Ref
import io.iohk.metronome.networking.NetworkTracers
import io.iohk.metronome.hotstuff.service.tracing.{
  ConsensusTracers,
  SyncTracers
}
import io.iohk.metronome.examples.robot.app.tracing._
import io.iohk.metronome.examples.robot.app.config.{RobotConfig, RobotOptions}
import io.iohk.metronome.logging.{InMemoryLogTracer, HybridLog}
import io.iohk.metronome.tracer.Tracer
import monix.eval.Task
import monix.execution.schedulers.TestScheduler

/** Test composition where we can override the default componetns
  *
  * Every test node should have its own composer, so we can track logs separately.
  */
class RobotTestComposition(scheduler: TestScheduler) extends RobotComposition {
  import RobotTestComposition._
  import RobotConsensusTracers.RobotConsensusEvent
  import RobotSyncTracers.RobotSyncEvent

  /** Start a node and return the variables that should be accessible in tests. */
  def composeEnv(
      opts: RobotOptions,
      config: RobotConfig
  ) = for {
    storages <- compose(opts, config)
    env <- Resource.make(
      Task.pure {
        RobotTestComposition.Env(
          storages,
          logTracer,
          consensusEventTracer,
          syncEventTracer
        )
      }
    )(_.clear)
  } yield env

  /** Class level log collector.
    *
    * If the composer is reused, this should be cleared between tests.
    */
  val logTracer            = InMemoryLogTracer.hybrid[Task]
  val consensusEventTracer = EventTracer[RobotConsensusEvent]
  val syncEventTracer      = EventTracer[RobotSyncEvent]

  private def makeLogTracer[T: HybridLog] =
    InMemoryLogTracer.hybrid[Task, T](logTracer)

  override protected def makeNetworkTracers = {
    import RobotNetworkTracers._
    NetworkTracers(makeLogTracer[RobotNetworkEvent])
  }

  override protected def makeConsensusTracers = {
    import RobotConsensusTracers._
    ConsensusTracers(
      makeLogTracer[RobotConsensusEvent] |+| consensusEventTracer
    )
  }

  override protected def makeSyncTracers = {
    import RobotSyncTracers._
    SyncTracers(makeLogTracer[RobotSyncEvent] |+| syncEventTracer)
  }

  // Use the `TestScheduler` to block on queries, otherwise the test hangs.
  override protected def makeDBBlocker =
    Resource.pure[Task, Blocker](Blocker.liftExecutionContext(scheduler))

  override protected def makeConnectionManager(
      config: RobotConfig,
      opts: RobotOptions
  )(implicit
      networkTracers: NTS
  ) = RobotTestConnectionManager(config, opts)
}

object RobotTestComposition {
  import RobotConsensusTracers.RobotConsensusEvent
  import RobotSyncTracers.RobotSyncEvent

  /** Collect events as they are. It's easier to pattern match than JSON logs. */
  class EventTracer[A](eventLogRef: Ref[Task, Vector[A]])
      extends Tracer[Task, A] {

    override def apply(a: => A): Task[Unit] =
      eventLogRef.update(_ :+ a)

    val clear     = eventLogRef.set(Vector.empty)
    val getEvents = eventLogRef.get
  }
  object EventTracer {
    def apply[A] =
      new EventTracer[A](Ref.unsafe[Task, Vector[A]](Vector.empty))
  }

  /** Things we may want to access in tests. */
  case class Env(
      storages: RobotComposition#Storages,
      logTracer: InMemoryLogTracer.HybridLogTracer[Task],
      consensusEventTracer: EventTracer[RobotConsensusEvent],
      syncEventTracer: EventTracer[RobotSyncEvent]
  ) {
    val clear =
      logTracer.clear >> consensusEventTracer.clear >> syncEventTracer.clear
  }
}
