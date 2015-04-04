package scorex.network

import java.net.InetSocketAddress
import java.util.logging.Logger
import akka.actor.{Actor, ActorRef}
import akka.io.Tcp._
import akka.util.ByteString
import scorex.block.NewBlock
import scorex.block.Block
import scorex.database.{UnconfirmedTransactionsDatabaseImpl, PrunableBlockchainStorage}
import scorex.network.NetworkController.UpdateHeight
import scorex.network.message.Message
import scorex.network.message._
import scorex.transaction.Transaction.TransactionType
import scala.collection.mutable
import scala.util.Random
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global


class PeerConnectionHandler(networkController: ActorRef,
                            connection: ActorRef,
                            remote: InetSocketAddress) extends Actor {

  import PeerConnectionHandler._

  val idsAwait = mutable.Buffer[Int]()

  context watch connection

  context.system.scheduler.schedule(500.millis, 2.seconds)(self ! PingRemote)
  context.system.scheduler.schedule(1.second, 15.seconds)(self ! SendHeight)

  def handleMessage(message: Message) = {
    message match {
      case PingMessage(id: Some[_]) => self ! PingMessage(mbId = id)

      case GetPeersMessage(idOpt: Some[Int]) =>
        self ! PeersMessage(PeerManager.knownPeers(), idOpt)

      case HeightMessage(height, _) => networkController ! UpdateHeight(remote, height)

      case SignaturesMessage(possibleParents, idOpt: Some[Int]) =>
        possibleParents.exists { parent =>
          val headers = PrunableBlockchainStorage.getSignatures(parent)
          if(headers.size > 0) {
            self ! SignaturesMessage(headers, idOpt)
            true
          } else false
        }

      case GetBlockMessage(signature, idOpt: Some[Int]) =>
        PrunableBlockchainStorage.blockByHeader(signature) match {
          case Some(block) => self ! BlockMessage(block.height().get, block, idOpt)
          case None => self ! Blacklist
        }

      case BlockMessage(height, block, _) =>
        require(block != null)

        if (Block.isNewBlockValid(block)) {
          networkController ! NewBlock(block, Some(remote))
        } else networkController ! UpdateHeight(remote, height)

      case TransactionMessage(transaction, _) =>
        //CHECK IF SIGNATURE IS VALID OR GENESIS TRANSACTION
        if (!transaction.isSignatureValid || transaction.transactionType == TransactionType.GENESIS_TRANSACTION) {
          self ! Blacklist
        } else if (transaction.hasMinimumFee && transaction.hasMinimumFeePerByte) {
          UnconfirmedTransactionsDatabaseImpl.put(transaction)
          networkController ! NetworkController.BroadcastMessage(message, List(remote))
        }

      case a:Any => Logger.getGlobal.warning(s"PeerConnectionHandler: got something strange $a")
    }
  }

  override def receive = {
    case PingRemote =>
      self ! PingMessage(mbId = Some(Random.nextInt(100000000) + 1))

    case SendHeight =>
      val height = PrunableBlockchainStorage.height()
      self ! HeightMessage(height, Some(Random.nextInt(100000000) + 1))

    case msg: Message =>
      self ! ByteString(msg.toBytes())

    case data: ByteString =>
      connection ! Write(data)

    case CommandFailed(w: Write) =>
      // O/S buffer was full
      Logger.getGlobal.info(s"Write failed : $w " + remote)
      PeerManager.blacklistPeer(remote)
      connection ! Close

    case Received(data) =>
      if (data.length <= Message.MAGIC_LENGTH || !data.take(Message.MAGIC_LENGTH).sameElements(Message.MAGIC)) {
        Logger.getGlobal.info(s"Corrupted data from: " + remote)
        connection ! Close
        //context stop self
      } else {
        val message = Message(data.drop(Message.MAGIC_LENGTH).toByteBuffer)
        Logger.getGlobal.finest("received message " + message.messageType + " from " + remote)

        //CHECK IF WE ARE WAITING FOR A MESSAGE WITH THAT ID
        message.mbId match {
          case Some(id) => if (!idsAwait.contains(id)) {
            Logger.getGlobal.info(s"Corrupted data (wrong id) from: " + remote)
            connection ! Close
          } else {
            idsAwait -= id
          }
          case _ =>
        }

        handleMessage(message)
      }

    case _: ConnectionClosed =>
      networkController ! NetworkController.PeerDisconnected(remote)
      Logger.getGlobal.info("Connection closed to : " + remote)

    case CloseConnection =>
      Logger.getGlobal.info(s"Enforced to abort communication with: " + remote)
      connection ! Close

    case Blacklist =>
      Logger.getGlobal.info(s"Going to blacklist " + remote)
      PeerManager.blacklistPeer(remote)
      connection ! Close
  }
}

object PeerConnectionHandler {

  case object PingRemote

  case object SendHeight

  case object CloseConnection

  case object Blacklist
}