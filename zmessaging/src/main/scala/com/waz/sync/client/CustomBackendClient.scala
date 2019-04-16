package com.waz.sync.client

import java.net.URL

import com.waz.api.impl.ErrorResponse
import com.waz.sync.client.CustomBackendClient.BackendConfigResponse
import com.waz.utils.CirceJSONSupport
import com.waz.znet2.http.{HttpClient, Method, RawBodyDeserializer, Request}

trait CustomBackendClient {
  def loadBackendConfig(url: URL): ErrorOrResponse[BackendConfigResponse]
}

class CustomBackendClientImpl(implicit httpClient: HttpClient)
  extends CustomBackendClient
    with CirceJSONSupport {

  import HttpClient.AutoDerivation._
  import HttpClient.dsl._

  private implicit val errorResponseDeserializer: RawBodyDeserializer[ErrorResponse] =
    objectFromCirceJsonRawBodyDeserializer[ErrorResponse]

  def loadBackendConfig(url: URL): ErrorOrResponse[BackendConfigResponse] = {
    Request.create(Method.Get, url)
      .withResultType[BackendConfigResponse]
      .withErrorType[ErrorResponse]
      .executeSafe
  }
}

object CustomBackendClient {
  case class BackendConfigResponse(endpoints: EndPoints)

  case class EndPoints(backendURL: String,
                       backendWSURL: String,
                       teamsURL: String,
                       accountsURL: String,
                       blackListURL: String,
                       websiteURL: String)
}