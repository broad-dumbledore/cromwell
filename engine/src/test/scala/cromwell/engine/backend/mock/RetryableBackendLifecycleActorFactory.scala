package cromwell.engine.backend.mock

import akka.actor.Props
import com.typesafe.config.Config
import cromwell.backend.{BackendJobDescriptor, BackendConfigurationDescriptor, BackendWorkflowDescriptor, BackendLifecycleActorFactory}
import wdl4s.Call

class RetryableBackendLifecycleActorFactory(config: Config) extends BackendLifecycleActorFactory {
  override def workflowInitializationActorProps(workflowDescriptor: BackendWorkflowDescriptor,
                                                calls: Seq[Call],
                                                configurationDescriptor: BackendConfigurationDescriptor): Option[Props] = None

  override def jobExecutionActorProps(jobDescriptor: BackendJobDescriptor,
                                      configurationDescriptor: BackendConfigurationDescriptor): Props = {
    RetryableBackendJobExecutionActor.props(jobDescriptor, configurationDescriptor)
  }

  override def workflowFinalizationActorProps(): Option[Props] = None
}
