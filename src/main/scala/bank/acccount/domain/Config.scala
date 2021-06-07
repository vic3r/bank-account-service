import bank.account.domain

import com.typesafe.config.ConfigFactory
import bank.account.model.ConfigProperty.ServerConfig

trait Config {

  private[this] lazy val loadConfig = ConfigFactory.load().getConfig("bank.account.server")

  lazy val serverConf = ServerConfig(loadConfig.getString("name"),
                                    loadConfig.getString("host"),
                                    loadConfig.getInt("port"),
                                    loadConfig.getInt("maxConcurrentRequests"),
                                    loadConfig.getInt("maxWaiters"),
  )
}