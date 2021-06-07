package bank.account.model

object ConfigProperty {
  case class ServerConfig(name: String,
                          host: String,
                          port: Int,
                          maxConcurrenceRequest: Int,
                          maxWaiters: Int)
}