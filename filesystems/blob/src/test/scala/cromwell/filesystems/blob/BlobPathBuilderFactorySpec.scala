package cromwell.filesystems.blob

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BlobPathBuilderFactorySpec extends AnyFlatSpec with Matchers {

  it should "parse configs for a functioning factory" in {
    val endpoint = BlobPathBuilderSpec.buildEndpoint("coaexternalstorage")
    val store = "inputs"
    val sasToken = "{SAS TOKEN HERE}"
    val workspaceId = "mockWorkspaceId"
    val workspaceManagerURL = "https://test.ws.org"
    val instanceConfig = ConfigFactory.parseString(
      s"""
      |filesystems.blob.instance {
      |  sas-token = "$sasToken"
      |  store = "$store"
      |  endpoint = "$endpoint"
      |  workspaceId = "$workspaceId"
      |}
      """.stripMargin)
    val globalConfig = ConfigFactory.parseString(s"""filesystems.blob.global.workspace-manager-url = "$workspaceManagerURL" """)
    val factory = BlobPathBuilderFactory(globalConfig, instanceConfig)
    factory.container should equal(store)
    factory.endpoint should equal(endpoint)
    factory.sasToken should equal(sasToken)
    factory.workspaceId should equal(sasToken)
    factory.workspaceManagerURL should equal(workspaceManagerURL)
  }
}
