package cromwell.backend.google.batch.runnable

import com.google.cloud.batch.v1.{Runnable, Volume}
import cromwell.backend.google.batch.api.GcpBatchRequestFactory.CreateBatchJobParameters


trait UserRunnable {

  def userRunnables(createParameters: CreateBatchJobParameters, volumes: List[Volume]): List[Runnable] = {

    println(f"job shell ${createParameters.jobShell}")
    println(f"script container path ${createParameters.commandScriptContainerPath}")

    val userRunnable = RunnableBuilder.userRunnable(
      docker = createParameters.dockerImage,
      scriptContainerPath = createParameters.commandScriptContainerPath.pathAsString,
      // TODO: determine path for shell and scatter
      //jobShell = createPipelineParameters.jobShell,
      jobShell = "/bin/bash",
      volumes = volumes,
      dockerhubCredentials = createParameters.dockerhubCredentials
    )

    val describeRunnable = RunnableBuilder.describeDocker("user runnable", userRunnable)
    List(describeRunnable, userRunnable).map(_.build)
  }
}
