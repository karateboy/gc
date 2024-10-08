import com.google.inject.AbstractModule
import models._
import play.api._
import play.api.libs.concurrent.AkkaGuiceSupport
/**
 * This class is a Guice module that tells Guice how to bind several
 * different types. This Guice module is created when the Play
 * application starts.

 * Play will automatically use any class called `Module` that is in
 * the root package. You can create modules in other locations by
 * adding `play.modules.enabled` settings to the `application.conf`
 * configuration file.
 */
class Module(environment: Environment,
             configuration: Configuration) extends AbstractModule with AkkaGuiceSupport {
  override def configure(): Unit = {
    bindActor[GcAgent]("GC_MainAgent")

    bindActorFactory[HaloKaAgent, HaloKaAgent.Factory]
    bindActorFactory[Adam6017Agent, Adam6017Agent.Factory]
  }

}
