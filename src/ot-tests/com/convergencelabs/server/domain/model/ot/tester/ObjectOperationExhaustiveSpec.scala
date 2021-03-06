package com.convergencelabs.convergence.server.backend.services.domain.model.ot

import scala.math.BigInt.int2bigInt
import org.json4s.JObject
import org.json4s.JsonAST.JInt
import org.json4s.JsonDSL.int2jvalue
import org.json4s.JsonDSL.pair2jvalue
import ObjectOperationExhaustiveSpec.InitialState
import com.convergencelabs.convergence.server.model.domain.model.DoubleValue

import scala.reflect.ClassTag

// scalastyle:off magic.number
object ObjectOperationExhaustiveSpec {
  val InitialState: JObject = JObject(List(("A" -> JInt(1))))

  val SetObject1 = Map("X" -> DoubleValue("1", 24))
  val SetObject2 = Map("Y" -> DoubleValue("1", 25))

  val SetObjects = List(SetObject1, SetObject2)

  val ExistingProperties = List("A", "B", "C")
  val NewProperties = List("D", "E", "F")
  val NewValues = List(DoubleValue("vid2", 4), DoubleValue("vid3", 5), DoubleValue("vid4", 6))
}

abstract class ObjectOperationExhaustiveSpec[S <: ObjectOperation, C <: ObjectOperation](implicit s: ClassTag[S], c: ClassTag[C])
    extends OperationPairExhaustiveSpec[MockObjectModel, S, C] {
  def createMockModel(): MockObjectModel = {
    new MockObjectModel(InitialState.values)
  }
}
