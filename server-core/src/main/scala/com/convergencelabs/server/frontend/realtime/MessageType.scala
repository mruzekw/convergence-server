package com.convergencelabs.server.frontend.realtime

object MessageType extends Enumeration {
  val Error = 0

  val Ping = 1
  val Pong = 2

  val HandshakeRequest = 3
  val HandshakeResponse = 4

  val PasswordAuthRequest = 5
  val TokenAuthRequest = 6
  val AuthenticationResponse = 7

  val OpenRealTimeModelRequest = 8
  val OpenRealTimeModelResponse = 9

  val CloseRealTimeModelRequest = 10
  val CloseRealTimeModelResponse = 11

  val CreateRealTimeModelRequest = 12
  val CreateRealTimeModelResponse = 13

  val DeleteRealtimeModelRequest = 14
  val DeleteRealtimeModelResponse = 15

  val ForceCloseRealTimeModel = 16

  val RemoteClientOpenedModel = 17
  val RemoteClientClosedModel = 18

  val ModelDataRequest = 19
  val ModelDataResponse = 20

  val RemoteOperation = 21

  val OperationSubmission = 22
  val OperationAck = 23

  val PublishReference = 24
  val SetReference = 25
  val ClearReference = 26
  val UnpublishReference = 27

  val ReferencePublished = 28
  val ReferenceSet = 29
  val ReferenceCleared = 30
  val ReferenceUnpublished = 31

  val UserLookUpRequest = 50
  val UserSearchRequest = 51
  val UserListResponse = 52
  
  val ActivityOpenRequest = 60
  val ActivityOpenResponse = 61

  val ActivityCloseRequest = 62
  val ActivityCloseResponse = 63

  val ActivityJoinRequest = 64
  val ActivityJoinResponse = 65

  val ActivityLeaveRequest = 66
  val ActivityLeaveResponse = 67

  val ActivitySessionJoined = 68
  val ActivitySessionLeft = 69

  val ActivityLocalStateSet = 70
  val ActivityLocalStateCleared = 71

  val ActivityRemoteStateSet = 72
  val ActivityRemoteStateCleared = 73
}