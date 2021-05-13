package io.iohk.metronome.examples.robot.app

import cats.effect.{ExitCode, Resource}
import monix.eval.{Task, TaskApp}
import io.iohk.metronome.crypto.{ECKeyPair, ECPublicKey}
import io.iohk.metronome.crypto.hash.Hash
import io.iohk.metronome.hotstuff.consensus.{Federation, LeaderSelection}
import io.iohk.metronome.hotstuff.service.Network
import io.iohk.metronome.hotstuff.service.messages.{
  DuplexMessage,
  HotStuffMessage
}
import io.iohk.metronome.networking.{
  ScalanetConnectionProvider,
  RemoteConnectionManager
}
import io.iohk.metronome.hotstuff.service.storage.{BlockStorage}
import io.iohk.metronome.examples.robot.RobotAgreement
import io.iohk.metronome.examples.robot.codecs.RobotCodecs
import io.iohk.metronome.examples.robot.models.RobotBlock
import io.iohk.metronome.examples.robot.service.RobotService
import io.iohk.metronome.examples.robot.service.messages.RobotMessage
import io.iohk.metronome.examples.robot.app.config.{
  RobotConfigParser,
  RobotConfig
}
import io.iohk.metronome.examples.robot.app.tracing.RobotNetworkTracers
import io.iohk.metronome.rocksdb.RocksDBStore
import io.iohk.metronome.storage.{KVStoreRunner, KVStoreRead, KVStore}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup
import io.iohk.metronome.storage.KVCollection
import java.security.SecureRandom
import scopt.OParser
import scodec.Codec

object RobotApp extends TaskApp {
  type NetworkMessage = DuplexMessage[RobotAgreement, RobotMessage]
  type Namespace      = RocksDBStore.Namespace

  case class CommandLineOptions(
      nodeIndex: Int = 0
  )

  def oparser(config: RobotConfig) = {
    val builder = OParser.builder[CommandLineOptions]
    import builder._

    OParser.sequence(
      programName("robot"),
      opt[Int]('i', "node-index")
        .action((i, opts) => opts.copy(nodeIndex = i))
        .text("index of example node to run")
        .required()
        .validate(i =>
          Either.cond(
            0 <= i && i < config.network.nodes.length,
            (),
            s"Must be between 0 and ${config.network.nodes.length - 1}"
          )
        )
    )
  }

  override def run(args: List[String]): Task[ExitCode] = {
    RobotConfigParser.parse match {
      case Left(error) =>
        Task.delay(println(error)).as(ExitCode.Error)
      case Right(config) =>
        OParser.parse(oparser(config), args, CommandLineOptions()) match {
          case None =>
            Task.pure(ExitCode.Error)
          case Some(opts) =>
            run(opts, config)
        }
    }
  }

  def run(opts: CommandLineOptions, config: RobotConfig): Task[ExitCode] =
    compose(opts, config).use(_ => Task.never.as(ExitCode.Success))

  implicit def `Codec[Set[T]]`[T: Codec] = {
    import scodec.codecs.implicits._
    Codec[List[T]].xmap[Set[T]](_.toSet, _.toList)
  }

  def compose(
      opts: CommandLineOptions,
      config: RobotConfig
  ): Resource[Task, Unit] = {
    import RobotCodecs._
    import RobotNetworkTracers.networkTracers
    implicit val scheduler = this.scheduler

    val federation =
      Federation(config.network.nodes.map(_.publicKey).toVector)(
        LeaderSelection.Hashing
      )

    val localNode = config.network.nodes(opts.nodeIndex)

    val dbConfig =
      RocksDBStore.Config.default(
        config.db.path.resolve(opts.nodeIndex.toString)
      )

    val clusterConfig = RemoteConnectionManager.ClusterConfig(
      clusterNodes = config.network.nodes.map { node =>
        node.publicKey -> node.address
      }.toSet
    )
    val retryConfig = RemoteConnectionManager.RetryConfig.default

    for {
      connectionProvider <- ScalanetConnectionProvider[
        Task,
        ECPublicKey,
        NetworkMessage
      ](
        bindAddress = localNode.address,
        nodeKeyPair = ECKeyPair(localNode.privateKey, localNode.publicKey),
        new SecureRandom(),
        useNativeTlsImplementation = true,
        framingConfig = DynamicTLSPeerGroup.FramingConfig
          .buildStandardFrameConfig(
            maxFrameLength = 1024 * 1024,
            lengthFieldLength = 8
          )
          .fold(e => sys.error(e.description), identity),
        maxIncomingQueueSizePerPeer = 100
      )

      connectionManager <- RemoteConnectionManager[
        Task,
        ECPublicKey,
        NetworkMessage
      ](connectionProvider, clusterConfig, retryConfig)

      rocksDbStore <- RocksDBStore[Task](dbConfig, RobotNamespaces.all)

      implicit0(storeRunner: KVStoreRunner[Task, Namespace]) =
        new KVStoreRunner[Task, Namespace] {
          override def runReadOnly[A](
              query: KVStoreRead[Namespace, A]
          ): Task[A] = rocksDbStore.runReadOnly(query)

          override def runReadWrite[A](query: KVStore[Namespace, A]): Task[A] =
            rocksDbStore.runWithBatching(query)
        }

      network = Network
        .fromRemoteConnnectionManager[Task, RobotAgreement, NetworkMessage](
          connectionManager
        )

      hotstuffAndApplicationNetworks <- Network.splitter[
        Task,
        RobotAgreement,
        NetworkMessage,
        HotStuffMessage[RobotAgreement],
        RobotMessage
      ](network)(
        split = {
          case DuplexMessage.AgreementMessage(m)   => Left(m)
          case DuplexMessage.ApplicationMessage(m) => Right(m)
        },
        merge = {
          case Left(m)  => DuplexMessage.AgreementMessage(m)
          case Right(m) => DuplexMessage.ApplicationMessage(m)
        }
      )

      blockStorage = new BlockStorage[Namespace, RobotAgreement](
        blockColl =
          new KVCollection[Namespace, Hash, RobotBlock](RobotNamespaces.Block),
        childToParentColl = new KVCollection[Namespace, Hash, Hash](
          RobotNamespaces.BlockToParent
        ),
        parentToChildrenColl = new KVCollection[Namespace, Hash, Set[Hash]](
          RobotNamespaces.BlockToChildren
        )
      )

      applicationService <- Resource.liftF {
        RobotService[Task, Namespace](
          maxRow = config.model.maxRow,
          maxCol = config.model.maxCol,
          network = hotstuffAndApplicationNetworks._2,
          blockStorage = blockStorage,
          viewStateStorage = ???,
          stateStorage = ???,
          simulatedDecisionTime = config.model.simulatedDecisionTime,
          timeout = config.network.timeout
        )
      }
    } yield ()
  }
}
