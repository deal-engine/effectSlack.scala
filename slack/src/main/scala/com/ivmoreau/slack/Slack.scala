package com.ivmoreau.slack

import com.slack.api.bolt.App;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import cats.effect.IO
import cats.effect.std.Queue
import com.slack.api.bolt.AppConfig
import cats.effect.std.Dispatcher
import com.slack.api.model.event.MessageEvent
import com.slack.api.app_backend.events.payload.EventsApiPayload
import fs2.Stream
import cats.effect.std.Env
import scala.util.Try
import cats.effect.IOApp
import cats.effect.std.Console
import com.slack.api.socket_mode.SocketModeClient
import org.slf4j
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.core.config.DefaultConfiguration
import com.slack.api.model.event.AppMentionEvent
import scala.concurrent.duration.Duration
import cats.effect.unsafe.implicits.global
import com.slack.api.methods.MethodsClient
import com.slack.api.model.event.Event
import scala.reflect.{ClassTag, classTag}

private object Logger {
  var logger: org.slf4j.Logger = null

  def debug(str: String): IO[Unit] = IO {
    logger.debug(str)
  }
}

class GenSlack[EventType <: Event: ClassTag](queue: Queue[IO, EventType])(
    implicit dispatch: Dispatcher[IO]
) {

  def genApp(botToken: String): IO[App] = for {
    _ <- IO.unit
    app = new App(AppConfig.builder().singleTeamBotToken(botToken).build())
    _ <-
      IO.blocking {
        app.event(
          classTag[EventType].runtimeClass.asInstanceOf[Class[EventType]],
          (req: EventsApiPayload[EventType], ctx) => {
            val event = req.getEvent()
            dispatch.unsafeRunSync(queue.offer(event))
            ctx.ack()
          }
        )
      }
  } yield app

  def genStream(
      times: Int
  )(botToken: String, appToken: String): Stream[IO, Stream[IO, EventType]] =
    Stream.eval(genApp(botToken)).flatMap { app =>
      Stream
        .eval(
          Logger.debug("Starting Slack app") *> IO.blocking {
            val socketModeApp: SocketModeApp = new SocketModeApp(
              appToken,
              SocketModeClient.Backend.JavaWebSocket,
              app
            )
            socketModeApp.start()
          }.start *> IO
            .sleep(Duration(50, scala.concurrent.duration.MILLISECONDS))
        )
        .evalMap { _ =>
          Logger.debug("Starting Slack stream") *> IO {
            Stream.eval(queue.take).repeat
          }
        }
        .repeatN(times)
    }
}

object SlackStream {
  def withTokens[EventType <: Event: ClassTag](
      times: Int
  )(botToken: String, appToken: String): Stream[IO, EventType] = {
    val empty: Stream[IO, EventType] = Stream.empty

    val resourceStream: Stream[IO, Dispatcher[IO]] = Stream.resource {
      Dispatcher.parallel[IO]
    }

    resourceStream.flatMap { implicit dispatcher =>
      Stream.eval {
        Queue
          .unbounded[IO, EventType]
          .map { queue =>
            (new GenSlack(queue))
              .genStream(times)(botToken, appToken)
              .chunkAll
              .flatMap { chunk =>
                chunk.foldLeft(empty) {
                  case (acc: Stream[IO, EventType], stream) =>
                    acc.merge(stream)
                }
              }
          }
      }.flatten
    }
  }

  def apply[EventType <: Event: ClassTag](times: Int): Stream[IO, EventType] = {

    Stream
      .eval(for {
        bot <- Env[IO].get("SLACK_BOT_TOKEN")
        app <- Env[IO].get("SLACK_APP_TOKEN")
        both <- IO.fromTry { Try { (bot.get, app.get) } }
      } yield both)
      .flatMap { case (bot, app) =>
        withTokens(times)(bot, app)
      }

  }
}
import com.slack.api.Slack;
class SlackMethods(client: MethodsClient) {
  def send2Channel(chan: String)(text: String): IO[Unit] = {
    Logger.debug(s"Sending message to Slack: $text at $chan") *>
      IO.blocking { client.chatPostMessage { _.channel(chan).text(text) } }.void
  }

  def withMethod[A](f: MethodsClient => A): IO[A] = {
    IO.blocking { f(client) }
  }
}

object SlackMethods {
  def apply(): IO[SlackMethods] = {
    val slackToken = Env[IO].get("SLACK_BOT_TOKEN").flatMap { maybe =>
      IO.fromOption(maybe)(new Exception("Maybe is None"))
    }

    slackToken.flatMap(withToken)
  }

  def withToken(slackToken: String): IO[SlackMethods] = {
    val slack = IO { Slack.getInstance() }
    val methods = slack.flatMap { a =>
      IO.blocking { a.methods(slackToken) }
    }
    methods.map(new SlackMethods(_))
  }
}

object test extends IOApp.Simple {
  def run: IO[Unit] = {
    Configurator.initialize(new DefaultConfiguration())
    Logger.logger = org.slf4j.LoggerFactory.getLogger("slackMessageStream")
    SlackStream[AppMentionEvent](5)
      .evalTap { a =>
        val newMsg =
          s"Hello ${a.getUser()}!, I'm a bot. You said ${a.getText()}. Thanks!"
        SlackMethods().map(_.send2Channel(a.getChannel())(newMsg))
      }
      .compile
      .drain
  }
}
