package xerial.silk.util

import java.util.concurrent.locks.ReentrantLock

/**
 * @author Taro L. Saito
 */
trait Guard {
  private[this] val lock = new ReentrantLock
  protected def newCondition = lock.newCondition

  protected def guard[U](f: => U): U = {
    try {
      lock.lock
      f
    }
    finally
      lock.unlock
  }
}
