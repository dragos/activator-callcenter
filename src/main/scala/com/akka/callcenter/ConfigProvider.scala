package com.akka.callcenter

object ConfigProvider {

  import com.typesafe.config.ConfigValue
  import com.typesafe.config.ConfigObject
  import com.typesafe.config.ConfigList

  case class ConfigTO(co: ConfigValue) {

    def foreach(func: (String, ConfigValue) => Unit) {
      co match {
        case sk: ConfigObject =>
          val iter = sk.keySet().iterator()
          while (iter.hasNext()) {
            val k = iter.next()
            func(k, sk.get(k))
          }
        case sk: ConfigList =>
          val iter = sk.iterator()
          while (iter.hasNext()) {
            val k = iter.next()
            func(k.toString(), k)
          }
      }
    }

    def foldLeft[T](initial: T)(func: (T, (String, ConfigValue)) => T): T = {
      var result = initial
      co match {
        case sk: ConfigObject =>
          val iter = sk.keySet().iterator()
          while (iter.hasNext()) {
            val k = iter.next()
            result = func(result, (k, sk.get(k)))
          }
          result
        case sk: ConfigList =>
          val iter = sk.iterator()
          while (iter.hasNext()) {
            val k = iter.next()
            result = func(result, (k.toString(), k))
          }
          result
      }
    }

  }

  implicit def configTransfer(co: ConfigValue) = ConfigTO(co)
}