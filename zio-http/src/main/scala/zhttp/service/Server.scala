package zhttp.service

import io.netty.util.{ResourceLeakDetector => JResourceLeakDetector}
import zhttp.core._
import zhttp.http.{Status, _}
import zhttp.service.server.{LeakDetectionLevel, ServerChannelFactory, ServerChannelInitializer, ServerRequestHandler}
import zio.{ZManaged, _}

sealed trait Server[-R, +E] { self =>

  import Server._

  def ++[R1 <: R, E1 >: E](other: Server[R1, E1]): Server[R1, E1] =
    Concat(self, other)

  private def settings[R1 <: R, E1 >: E](s: Settings[R1, E1] = Settings()): Settings[R1, E1] = self match {
    case Concat(self, other)  => other.settings(self.settings(s))
    case Port(port)           => s.copy(port = port)
    case LeakDetection(level) => s.copy(leakDetectionLevel = level)
    case App(http)            => s.copy(http = http)
    case MaxRequestSize(size) => s.copy(maxRequestSize = size)
  }

  def make[E1 >: E: SilentResponse]: ZManaged[R with EventLoopGroup, Throwable, Unit] = Server.make(self)

  def start[E1 >: E: SilentResponse]: ZIO[R with EventLoopGroup, Throwable, Nothing] = make.useForever
}

object Server {
  private case class Settings[-R, +E](
    http: HttpApp[R, E] = Http.empty(Status.NOT_FOUND),
    port: Int = 8080,
    leakDetectionLevel: LeakDetectionLevel = LeakDetectionLevel.SIMPLE,
    maxRequestSize: Int = 4 * 1024, // 4 kilo bytes
  )

  private case class Concat[R, E](self: Server[R, E], other: Server[R, E]) extends Server[R, E]
  private case class Port(port: Int)                                       extends UServer
  private case class LeakDetection(level: LeakDetectionLevel)              extends UServer
  private case class MaxRequestSize(size: Int)                             extends UServer
  private case class App[R, E](http: HttpApp[R, E])                        extends Server[R, E]

  def app[R, E](http: HttpApp[R, E]): Server[R, E] = Server.App(http)
  def maxRequestSize(size: Int): UServer           = Server.MaxRequestSize(size)
  def port(int: Int): UServer                      = Server.Port(int)
  val disableLeakDetection: UServer                = LeakDetection(LeakDetectionLevel.DISABLED)
  val simpleLeakDetection: UServer                 = LeakDetection(LeakDetectionLevel.SIMPLE)
  val advancedLeakDetection: UServer               = LeakDetection(LeakDetectionLevel.ADVANCED)
  val paranoidLeakDetection: UServer               = LeakDetection(LeakDetectionLevel.PARANOID)

  /**
   * Launches the app on the provided port.
   */
  def start[R <: Has[_], E: SilentResponse](port: Int, http: HttpApp[R, E]): ZIO[R, Throwable, Nothing] =
    (Server.port(port) ++ Server.app(http)).make.useForever
      .provideSomeLayer[R](EventLoopGroup.auto())

  def make[R, E: SilentResponse](server: Server[R, E]): ZManaged[R with EventLoopGroup, Throwable, Unit] = {
    for {
      zExec          <- UnsafeChannelExecutor.make[R].toManaged_
      channelFactory <- ServerChannelFactory.Live.auto.toManaged_
      eventLoopGroup <- ZIO.access[EventLoopGroup](_.get).toManaged_
      settings        = server.settings()
      httpH           = ServerRequestHandler(zExec, settings.http)
      init            = ServerChannelInitializer(httpH, settings.maxRequestSize)
      serverBootstrap = new JServerBootstrap().channelFactory(channelFactory).group(eventLoopGroup)
      _ <- ChannelFuture.asManaged(serverBootstrap.childHandler(init).bind(settings.port))
    } yield {
      JResourceLeakDetector.setLevel(settings.leakDetectionLevel.jResourceLeakDetectionLevel)
    }
  }
}
