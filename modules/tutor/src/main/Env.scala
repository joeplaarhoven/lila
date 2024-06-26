package lila.tutor

import com.softwaremill.macwire.*
import com.softwaremill.tagging.*

import lila.core.config
import lila.db.dsl.Coll
import lila.core.fishnet.{ AnalysisAwaiter, SystemAnalysisRequest }
import lila.memo.CacheApi

@Module
final class Env(
    db: lila.db.Db,
    userApi: lila.user.UserApi,
    gameRepo: lila.game.GameRepo,
    fishnetAwaiter: AnalysisAwaiter,
    fishnetSystem: SystemAnalysisRequest,
    insightApi: lila.insight.InsightApi,
    perfStatsApi: lila.insight.InsightPerfStatsApi,
    settingStore: lila.memo.SettingStore.Builder,
    cacheApi: CacheApi,
    lightUserApi: lila.user.LightUserApi
)(using Executor, Scheduler, play.api.Mode, akka.stream.Materializer):

  private val colls = TutorColls(db(config.CollName("tutor_report")), db(config.CollName("tutor_queue")))

  lazy val nbAnalysisSetting = settingStore[Int](
    "tutorNbAnalysis",
    default = 30,
    text = "Number of fishnet analysis per tutor build".some
  ).taggedWith[NbAnalysis]

  lazy val parallelismSetting = settingStore[Int](
    "tutorParallelism",
    default = 3,
    text = "Number of tutor reports to build in parallel".some
  ).taggedWith[Parallelism]

  private lazy val fishnet = wire[TutorFishnet]
  private lazy val builder = wire[TutorBuilder]
  lazy val queue           = wire[TutorQueue]

  lazy val api = wire[TutorApi]

final private class TutorColls(val report: Coll, val queue: Coll)
trait NbAnalysis
trait Parallelism
