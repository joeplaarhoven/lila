package lila.memo

import akka.actor.ActorSystem
import com.github.blemale.scaffeine.{ AsyncLoadingCache, Cache, Scaffeine }
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

final class AsyncCache[K, V](cache: AsyncLoadingCache[K, V], f: K => Fu[V]) {

  def get(k: K): Fu[V] = cache get k

  def refresh(k: K): Unit = cache.put(k, f(k))

  def put(k: K, v: V): Unit = cache.put(k, fuccess(v))
}

final class AsyncCacheClearable[K, V](
    cache: Cache[K, Fu[V]],
    f: K => Fu[V],
    logger: lila.log.Logger
)(implicit ec: ExecutionContext) {

  def get(k: K): Fu[V] =
    cache.get(
      k,
      k =>
        f(k).addFailureEffect { err =>
          logger.warn(s"$k $err")
          cache invalidate k
        }
    )

  def update(k: K, f: V => V): Unit =
    cache.getIfPresent(k) foreach { fu =>
      cache.put(k, fu map f)
    }

  def put(k: K, v: Fu[V]): Unit = cache.put(k, v)

  def invalidate(k: K): Unit = cache invalidate k

  def invalidateAll(): Unit = cache.invalidateAll
}

final class AsyncCacheSingle[V](cache: AsyncLoadingCache[Unit, V], f: Unit => Fu[V]) {

  def get: Fu[V] = cache.get(())

  def refresh(): Unit = cache.put((), f(()))
}

object AsyncCache {

  final class Builder(api: CacheApi)(implicit ec: ExecutionContext, system: ActorSystem) {

    def multi[K, V](
        name: String,
        f: K => Fu[V],
        maxCapacity: Int = 32768,
        expireAfter: AsyncCache.type => ExpireAfter,
        resultTimeout: FiniteDuration = 5 seconds
    ) = {
      val safeF = (k: K) =>
        f(k).withTimeout(
          resultTimeout,
          lila.base.LilaException(s"AsyncCache.multi $name key=$k timed out after $resultTimeout")
        )
      val cache: AsyncLoadingCache[K, V] = makeExpire(
        api.scaffeine.maximumSize(maxCapacity),
        expireAfter
      ).recordStats.buildAsyncFuture(safeF)
      api.monitor(name, cache.underlying.synchronous)
      new AsyncCache[K, V](cache, safeF)
    }

    def clearable[K, V](
        name: String,
        f: K => Fu[V],
        maxCapacity: Int = 32768,
        expireAfter: AsyncCache.type => ExpireAfter,
        resultTimeout: FiniteDuration = 5 seconds
    ) = {
      val fullName = s"AsyncCache.clearable $name"
      val safeF = (k: K) =>
        f(k).withTimeout(
          resultTimeout,
          lila.base.LilaException(s"$fullName key=$k timed out after $resultTimeout")
        )
      val cache: Cache[K, Fu[V]] = makeExpire(
        api.scaffeine.maximumSize(maxCapacity),
        expireAfter
      ).recordStats.build[K, Fu[V]]
      api.monitor(name, cache.underlying)
      new AsyncCacheClearable[K, V](cache, safeF, logger = logger branch fullName)
    }

    def single[V](
        name: String,
        f: => Fu[V],
        expireAfter: AsyncCache.type => ExpireAfter,
        resultTimeout: FiniteDuration = 5 seconds,
        monitor: Boolean = true
    ) = {
      val safeF = (_: Unit) =>
        f.withTimeout(
          resultTimeout,
          lila.base.LilaException(s"AsyncCache.single $name single timed out after $resultTimeout")
        )
      val builder = makeExpire(api.scaffeine.maximumSize(1), expireAfter)
      if (monitor) builder.recordStats
      val cache: AsyncLoadingCache[Unit, V] = builder.buildAsyncFuture(safeF)
      if (monitor) api.monitor(name, cache.underlying.synchronous)
      new AsyncCacheSingle[V](cache, safeF)
    }
  }

  sealed trait ExpireAfter
  case class ExpireAfterAccess(duration: FiniteDuration) extends ExpireAfter
  case class ExpireAfterWrite(duration: FiniteDuration)  extends ExpireAfter

  private def makeExpire[K, V](
      builder: Scaffeine[K, V],
      expireAfter: AsyncCache.type => ExpireAfter
  ): Scaffeine[K, V] = expireAfter(AsyncCache) match {
    case ExpireAfterAccess(duration) => builder expireAfterAccess duration
    case ExpireAfterWrite(duration)  => builder expireAfterWrite duration
  }
}
