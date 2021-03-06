/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import internal._
import akka.actor._
import org.apache.camel.ProducerTemplate
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.model.RouteDefinition
import com.typesafe.config.Config
import akka.ConfigurationException
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit._
import scala.concurrent.duration.FiniteDuration

/**
 * Camel trait encapsulates the underlying camel machinery.
 * '''Note:''' `CamelContext` and `ProducerTemplate` are stopped when the associated actor system is shut down.
 * This trait can be obtained through the [[akka.camel.CamelExtension]] object.
 */
trait Camel extends Extension with Activation {
  /**
   * Underlying camel context.
   *
   * It can be used to configure camel manually, i.e. when the user wants to add new routes or endpoints,
   * i.e. {{{camel.context.addRoutes(...)}}}
   *
   * @see [[org.apache.camel.impl.DefaultCamelContext]]
   */
  def context: DefaultCamelContext

  /**
   * The Camel ProducerTemplate.
   * @see [[org.apache.camel.ProducerTemplate]]
   */
  def template: ProducerTemplate

  /**
   * The settings for the CamelExtension
   */
  def settings: CamelSettings

  /**
   * For internal use only. Returns the camel supervisor actor.
   */
  private[camel] def supervisor: ActorRef

  /**
   * For internal use only. Returns the associated ActorSystem.
   */
  private[camel] def system: ActorSystem
}

/**
 * Settings for the Camel Extension
 * @param config the config
 */
class CamelSettings private[camel] (config: Config, dynamicAccess: DynamicAccess) {
  /**
   * Configured setting for how long the actor should wait for activation before it fails.
   */
  final val ActivationTimeout: FiniteDuration = Duration(config.getMilliseconds("akka.camel.consumer.activation-timeout"), MILLISECONDS)

  /**
   * Configured setting, when endpoint is out-capable (can produce responses) replyTimeout is the maximum time
   * the endpoint can take to send the response before the message exchange fails.
   * This setting is used for out-capable, in-only, manually acknowledged communication.
   */
  final val ReplyTimeout: FiniteDuration = Duration(config.getMilliseconds("akka.camel.consumer.reply-timeout"), MILLISECONDS)

  /**
   * Configured setting which determines whether one-way communications between an endpoint and this consumer actor
   * should be auto-acknowledged or application-acknowledged.
   * This flag has only effect when exchange is in-only.
   */
  final val AutoAck: Boolean = config.getBoolean("akka.camel.consumer.auto-ack")

  /**
   *
   */
  final val JmxStatistics: Boolean = config.getBoolean("akka.camel.jmx")

  /**
   * enables or disables streamingCache on the Camel Context
   */
  final val StreamingCache: Boolean = config.getBoolean("akka.camel.streamingCache")

  final val Conversions: (String, RouteDefinition) ⇒ RouteDefinition = {
    import scala.collection.JavaConverters.asScalaSetConverter
    val specifiedConversions = {
      val section = config.getConfig("akka.camel.conversions")
      section.entrySet.asScala.map(e ⇒ (e.getKey, section.getString(e.getKey)))
    }
    val conversions = (Map[String, Class[_ <: AnyRef]]() /: specifiedConversions) {
      case (m, (key, fqcn)) ⇒
        m.updated(key, dynamicAccess.getClassFor[AnyRef](fqcn).recover {
          case e ⇒ throw new ConfigurationException("Could not find/load Camel Converter class [" + fqcn + "]", e)
        }.get)
    }

    (s: String, r: RouteDefinition) ⇒ conversions.get(s).fold(r)(r.convertBodyTo)
  }

}
/**
 * This class can be used to get hold of an instance of the Camel class bound to the actor system.
 * <p>For example:
 * {{{
 * val system = ActorSystem("some system")
 * val camel = CamelExtension(system)
 * camel.context.addRoutes(...)
 * }}}
 *
 * @see akka.actor.ExtensionId
 * @see akka.actor.ExtensionIdProvider
 *
 */
object CamelExtension extends ExtensionId[Camel] with ExtensionIdProvider {

  /**
   * Creates a new instance of Camel and makes sure it gets stopped when the actor system is shutdown.
   */
  override def createExtension(system: ExtendedActorSystem): Camel = {
    val camel = new DefaultCamel(system).start
    system.registerOnTermination(camel.shutdown())
    camel
  }

  override def lookup(): ExtensionId[Camel] = CamelExtension

  override def get(system: ActorSystem): Camel = super.get(system)
}
