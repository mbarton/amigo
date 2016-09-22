package packer

import java.nio.file.Paths

import cats.Id
import cats.data.{ Reader, ReaderT }
import cats.instances.future._
import event.{ BakeEvent, EventBus }
import models.{ Bake, BakeStatus, BuildResult }
import models.packer.PackerBuildConfig
import org.joda.time.DateTime
import packer.BuildService.CreateImageContext
import play.api.libs.ws.WSClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Success
import org.scalatest._
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalacheck.{ Arbitrary, Gen }
import org.scalacheck.Shapeless._

class BuildServiceSpec extends FlatSpec with Matchers with ScalaFutures with IntegrationPatience {

  it should "do what it's supposed to do" in {
    val eventBus = new EventBus {
      override def publish(event: BakeEvent): Unit = {}
    }
    val context = CreateImageContext(
      eventBus,
      PackerConfig(stage = "TEST", vpcId = None, subnetId = None, instanceProfile = None),
      wsClient = null
    )

    implicit val arbBakeStatus = Arbitrary(Gen.const(BakeStatus.Running))
    implicit val arbDateTime = Arbitrary(Gen.const(new DateTime))
    implicit val arbBake = implicitly[Arbitrary[Bake]]
    val bake = Arbitrary.arbitrary[Bake].sample.get

    val future = BuildService.createImage(
      generatePlaybook = _ => "here is the playbook",
      writePlaybookToTempFile = (_, _) => Success(Paths.get("playbook.yml")),
      findAllAWSAccountNumbers = ReaderT.pure[Future, WSClient, Seq[String]](List("my-account-id")),
      generatePackerBuildConfig = (_, _, _) => ReaderT.pure[Id, PackerConfig, PackerBuildConfig](PackerBuildConfig(variables = Map.empty, builders = Nil, provisioners = Nil)),
      writePackerConfigToTempFile = (_, _) => Success(Paths.get("packer.json")),
      executePacker = _ => ReaderT.pure[Future, EventBus, Int](0)
    )(bake).run(context)

    whenReady(future) { value => value should be(BuildResult.Success) }
  }

}
