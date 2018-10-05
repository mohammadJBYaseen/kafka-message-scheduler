package com.sky.kms.e2e

import java.util.UUID

import akka.testkit.{TestActor, TestProbe}
import com.sky.kms.actors.SchedulingActor.{Ack, CreateOrUpdate, Init}
import com.sky.kms.avro._
import com.sky.kms.base.SchedulerIntBaseSpec
import com.sky.kms.common.TestDataUtils._
import com.sky.kms.config._
import com.sky.kms.domain.{ScheduleEvent, ScheduleId}
import com.sky.kms.streams.ScheduleReader
import kafka.admin.AdminUtils
import kafka.utils.ZkUtils
import org.scalatest.Assertion

import scala.concurrent.Await
import scala.concurrent.duration._

class ScheduleEventReaderIntSpec extends SchedulerIntBaseSpec {

  val NumSchedules = 10

  lazy val zkUtils = ZkUtils(zkServer, 3000, 3000, isZkSecurityEnabled = false)

  override def afterAll() {
    zkUtils.close()
    super.afterAll()
  }

  "stream" should {
    "consume all committed schedules from topic on restart" in {
      AdminUtils.createTopic(zkUtils, conf.scheduleTopic.head, partitions = 20, replicationFactor = 1)

      val firstSchedule :: newSchedules = List.fill(NumSchedules)(generateSchedules)

      withRunningScheduleReader { probe =>
        writeSchedulesToKafka(firstSchedule)

        probe.expectMsgType[CreateOrUpdate](5 seconds).scheduleId shouldBe firstSchedule._1
      }

      withRunningScheduleReader { probe =>
        writeSchedulesToKafka(newSchedules: _*)

        val newScheduleIds = newSchedules.map { case (scheduleId, _) => scheduleId }
        val receivedScheduleIds = List.fill(newScheduleIds.size)(probe.expectMsgType[CreateOrUpdate](5 seconds).scheduleId)

        receivedScheduleIds should contain theSameElementsAs newScheduleIds
      }
    }
  }

  private def generateSchedules: (ScheduleId, ScheduleEvent) =
    (UUID.randomUUID().toString, random[ScheduleEvent])

  private def withRunningScheduleReader(scenario: TestProbe => Assertion) {
    val probe = TestProbe()

    probe.setAutoPilot((sender, msg) => msg match {
      case _ =>
        sender ! Ack
        TestActor.KeepRunning
    })

    val scheduleReader = ScheduleReader.configure(probe.testActor).apply(AppConfig(conf))
    val (running, _) = scheduleReader.stream.run()

    probe.expectMsg(Init)

    scenario(probe)

    Await.ready(running.shutdown(), 5 seconds)
  }

  private def writeSchedulesToKafka(schedules: (ScheduleId, ScheduleEvent)*) {
    writeToKafka(ScheduleTopic.head, schedules.map { case (scheduleId, schedule) => (scheduleId, schedule.toAvro) }: _*)
  }

}
