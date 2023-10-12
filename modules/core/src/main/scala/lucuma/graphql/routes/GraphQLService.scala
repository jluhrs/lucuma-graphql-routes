// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.MonadThrow
import cats.data.NonEmptyChain
import cats.syntax.all._
import edu.gemini.grackle
import edu.gemini.grackle.Mapping
import edu.gemini.grackle.Operation
import edu.gemini.grackle.Problem
import edu.gemini.grackle.Result
import fs2.Compiler
import fs2.Stream
import io.circe.Json
import io.circe.JsonObject
import natchez.Trace

class GraphQLService[F[_]: MonadThrow: Trace](
  val mapping: Mapping[F],
)(implicit ev: Compiler[F,F]) {

  def isSubscription(op: Operation): Boolean =
    mapping.schema.subscriptionType.exists(_ =:= op.rootTpe)

  def parse(query: String, op: Option[String], vars: Option[JsonObject]): Result[Operation] =
    mapping.compiler.compile(query, op, vars.map(_.toJson))

  def query(op: Operation): F[Result[Json]] =
    Trace[F].span("graphql") {
      Trace[F].put("graphql.query" -> op.query.render) *>
      subscribe(op).compile.toList.map {
        case List(e) => e
        case other   => Result.internalError(GrackleException(Problem(s"Expected exactly one result, found ${other.length}.")))
      }
    }

  def subscribe(op: Operation): Stream[F, Result[Json]] =
    mapping.interpreter.run(op.query, op.rootTpe, grackle.Env.EmptyEnv)

}

case class GrackleException(problems: List[Problem]) extends Exception
object GrackleException {
  def apply(problems: NonEmptyChain[Problem]): GrackleException = apply(problems.toList)
  def apply(problem: Problem): GrackleException = apply(List(problem))
}

