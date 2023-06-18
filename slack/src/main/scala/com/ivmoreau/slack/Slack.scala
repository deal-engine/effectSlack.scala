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

class Slacka {}

object Logger {
  var logger: org.slf4j.Logger = null

  def info(str: String): IO[Unit] = IO {
    logger.info(str)
  }
}

type RET = AppMentionEvent

object S {

  def genApp(botToken: String): IO[(App, Queue[IO, RET])] = for {
    queue <- Queue.unbounded[IO, RET]
    app = new App(AppConfig.builder().singleTeamBotToken(botToken).build())
    _ <-
      IO {
        app.event(
          classOf[MessageEvent],
          (req: EventsApiPayload[MessageEvent], ctx) => {
            val aa = req.getEvent()
            Logger
              .info(s"Received a message from Slack: ${aa.getUser()}")
              .unsafeRunSync()
            ctx.ack()
          }
        )
      }
    _ <-
      IO {
        app.event(
          classOf[AppMentionEvent],
          (req: EventsApiPayload[AppMentionEvent], ctx) => {
            val aa = req.getEvent()
            Logger
              .info(
                s"Received a mention from Slack: ${aa.getUser()} ${aa.getText()}"
              )
              .unsafeRunSync()
            queue.offer(aa).unsafeRunSync()
            ctx.ack()
          }
        )
      }
  } yield (app, queue)

  def genStream(
      times: Int
  )(botToken: String, appToken: String): Stream[IO, Stream[IO, RET]] =
    Stream.eval(genApp(botToken)).flatMap { case (app, queue) =>
      Stream
        .eval(
          Logger.info("Starting Slack app") *> IO.blocking {
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
          Logger.info("Starting Slack stream") *> IO {
            Stream.fromQueueUnterminated(queue)
          }
        }
        .repeatN(times)
    }
}

object Slacka {
  def withTokens(
      times: Int
  )(botToken: String, appToken: String): Stream[IO, RET] = {
    val empty: Stream[IO, RET] = Stream.empty
    S.genStream(times)(botToken, appToken).chunkAll.flatMap { chunk =>
      chunk.foldLeft(empty) { case (acc: Stream[IO, RET], stream) =>
        acc.merge(stream)
      }
    }
  }

  def apply(times: Int): Stream[IO, RET] = {

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
class SlackRespond(m: IO[MethodsClient]) {
  def send2Channel(chan: String)(text: String): IO[Unit] = {
    Logger.info(s"Sending message to Slack: $text at $chan") *>
      m.flatMap { a =>
        IO.blocking { a.chatPostMessage { _.channel(chan).text(text) } }
      }.void
  }
}

object SlackRespond {
  def apply(): IO[SlackRespond] = {
    Env[IO].get("SLACK_APP_TOKEN").flatMap { maybe =>
      IO.fromOption(maybe)(new Exception("Maybe is None"))
        .flatMap(SlackRespond.withToken)
    }
  }

  def withToken(slackToken: String): IO[SlackRespond] = {
    val slack = IO { Slack.getInstance() }
    val methods = slack.flatMap { a =>
      IO.blocking { a.methods(slackToken) }
    }
    IO { new SlackRespond(methods) }
  }
}

object test extends IOApp.Simple {
  def run: IO[Unit] = {
    Configurator.initialize(new DefaultConfiguration())
    Logger.logger = org.slf4j.LoggerFactory.getLogger("slackMessageStream")
    Slacka(5)
      .evalTap { a =>
        val newMsg =
          s"Hello ${a.getUser()}!, I'm a bot. You said ${a.getText()}. Thanks!"
        SlackRespond()
          .flatMap { _.send2Channel(a.getChannel())(newMsg) }
      }
      .compile
      .drain
  }
}
