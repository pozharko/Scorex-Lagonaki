package scorex

import _root_.unit.CryptoSpecification
import org.scalatest.Suites
import scorex.integration.BlocksRoutingSpecification
import scorex.unit._

class ScorexTestSuite extends Suites(
  //unit tests
  new CryptoSpecification
  ,new MessageSpecification
  ,new TransactionSpecification
  ,new BlockSpecification
  ,new BlockchainStorageSpecification
  ,new WalletSpecification
  ,new BlocksRoutingSpecification

  //integration tests - slow!
  // todo:uncomment after fixing problems with test stopping
  //,new ValidChainGenerationSpecification
)
