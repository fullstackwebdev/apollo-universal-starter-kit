package controllers.graphql

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.ws.{Message, TextMessage, UpgradeToWebSocket}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, KillSwitches, OverflowStrategy, SharedKillSwitch}
import controllers.graphql.websocket.OperationMessage
import controllers.graphql.websocket.OperationMessageJsonProtocol._
import controllers.graphql.websocket.OperationMessageType._
import graphql.{GraphQLContext, GraphQLContextFactory}
import javax.inject.{Inject, Singleton}
import monix.execution.Scheduler
import monix.reactive.Observable
import sangria.ast.OperationType.Subscription
import sangria.execution.Executor
import sangria.marshalling.sprayJson._
import sangria.parser.{QueryParser, SyntaxError}
import spray.json._

import scala.util.{Failure, Success}

@Singleton
class WebSocketHandler @Inject()(graphQlContextFactory: GraphQLContextFactory,
                                 graphQlExecutor: Executor[GraphQLContext, Unit])
                                (implicit val actorMaterializer: ActorMaterializer,
                                 implicit val scheduler: Scheduler) {

  private val graphqlWebsocketProtocol = Some("graphql-ws")

  def handleQuery(upgradeToWebSocket: UpgradeToWebSocket): HttpResponse = {
    implicit val (queue, publisher) = Source.queue[Message](0, OverflowStrategy.fail)
      .toMat(Sink.asPublisher(false))(Keep.both)
      .run()
    val killSwitches = KillSwitches.shared(this.getClass.getSimpleName)
    val incoming = Flow[Message]
      .collect {
        case TextMessage.Strict(query) =>
          val operation = query.parseJson.convertTo[OperationMessage]
          operation.operationType match {
            case GQL_CONNECTION_INIT =>
              reply(OperationMessage(GQL_CONNECTION_ACK, None, None))
            case GQL_START =>
              handleGraphQlQuery(operation, killSwitches)
          }
      }
      .to {
        Sink.onComplete {
          _ =>
            killSwitches.shutdown
            queue.complete
        }
      }
    upgradeToWebSocket.handleMessagesWithSinkSource(incoming, Source.fromPublisher(publisher), graphqlWebsocketProtocol)
  }

  private def handleGraphQlQuery(operationMessage: OperationMessage, killSwitches: SharedKillSwitch)
                                (implicit queue: SourceQueueWithComplete[Message]): Unit = {
    import sangria.execution.ExecutionScheme.Stream
    import sangria.streaming.monix._
    operationMessage.payload.foreach {
      payload =>
        val JsObject(fields) = payload
        val JsString(query) = fields("query")
        val operation = fields.get("operationName") collect {
          case JsString(op) => op
        }
        val vars = fields.get("variables") match {
          case Some(obj: JsObject) => obj
          case _ => JsObject.empty
        }
        QueryParser.parse(query) match {
          case Success(queryAst) =>
            queryAst.operationType(operation) match {
              case Some(Subscription) =>
                val ctx = graphQlContextFactory.createContextForRequest
                val observable: Observable[JsValue] = graphQlExecutor.execute(
                  queryAst = queryAst,
                  userContext = ctx,
                  root = (),
                  operationName = operation,
                  variables = vars
                )
                Source.fromPublisher(observable.toReactivePublisher)
                  .viaMat(killSwitches.flow)(Keep.none)
                  .runForeach {
                    result =>
                      reply(OperationMessage(GQL_DATA, operationMessage.id, Some(result)))
                  }
              case _ =>
                reply(OperationMessage(
                  GQL_ERROR,
                  operationMessage.id,
                  Some(s"Unsupported type: ${queryAst.operationType(None)}".toJson)
                ))
            }
          case Failure(e: SyntaxError) =>
            val syntaxError = JsObject(
              "syntaxError" -> JsString(e.getMessage),
              "locations" -> JsArray(
                JsObject(
                  "line" -> JsNumber(e.originalError.position.line),
                  "column" -> JsNumber(e.originalError.position.column)
                )
              )
            )
            reply(OperationMessage(
              GQL_ERROR,
              operationMessage.id,
              Some(syntaxError)
            ))
          case Failure(_) =>
            reply(OperationMessage(
              GQL_ERROR,
              operationMessage.id,
              Some("Internal Server Error".toJson)
            ))
        }
    }
  }

  private def reply(operationMessage: OperationMessage)(implicit queue: SourceQueueWithComplete[Message]): Unit = {
    queue.offer(TextMessage(operationMessage.toJson.toString))
  }
}