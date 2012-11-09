/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import scala.util.Try
import akka.actor.Address
import akka.testkit.AkkaSpec
import akka.cluster.StandardMetrics.HeapMemory
import akka.cluster.StandardMetrics.Cpu
import akka.cluster.StandardMetrics.NetworkIO

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricValuesSpec extends AkkaSpec(MetricsEnabledSpec.config) with MetricsCollectorFactory {

  val collector = createMetricsCollector

  val node1 = NodeMetrics(Address("akka", "sys", "a", 2554), 1, collector.sample.metrics)
  val node2 = NodeMetrics(Address("akka", "sys", "a", 2555), 1, collector.sample.metrics)

  var nodes: Seq[NodeMetrics] = Seq(node1, node2)

  // work up the data streams where applicable
  for (i ← 1 to 100) {
    nodes = nodes map {
      n ⇒
        n.copy(metrics = collector.sample.metrics.flatMap(latest ⇒ n.metrics.collect {
          case streaming if latest same streaming ⇒
            streaming.average match {
              case Some(e) ⇒ streaming.copy(value = latest.value, average =
                Some(e :+ latest.value.doubleValue))
              case None ⇒ streaming.copy(value = latest.value)
            }
        }))
    }
  }

  "NodeMetrics.MetricValues" must {
    "extract expected metrics for load balancing" in {
      import HeapMemory.Fields._
      val stream1 = node2.metric(HeapMemoryCommitted).get.value.longValue
      val stream2 = node1.metric(HeapMemoryUsed).get.value.longValue
      stream1 must be >= (stream2)
    }

    "extract expected MetricValue types for load balancing" in {
      nodes foreach { node ⇒
        node match {
          case HeapMemory(heap) ⇒
            heap.committed must be >= (heap.used)
            heap.max match {
              case Some(m) ⇒
                heap.used must be <= (m)
                heap.committed must be <= (m)
              case None ⇒
                heap.used must be > (0L)
                heap.committed must be > (0L)
            }
          case _ ⇒ fail("no heap")
        }

        node match {
          case NetworkIO(net) ⇒
            net.inbound must be >= (0.0)
            net.outbound must be >= (0.0)
          case _ ⇒ // ok, only collected by sigar
        }

        node match {
          case Cpu(cpu) ⇒
            cpu.processors must be > (0)
            if (cpu.systemLoadAverage.isDefined)
              cpu.systemLoadAverage.get must be >= (0.0)
            if (cpu.cpuCombined.isDefined) {
              cpu.cpuCombined.get must be <= (1.0)
              cpu.cpuCombined.get must be >= (0.0)
            }
            if (cpu.cores.isDefined) {
              cpu.cores.get must be > (0)
              cpu.cores.get must be >= (cpu.processors)
            }
          case _ ⇒ fail("no cpu")
        }
      }
    }
  }

}