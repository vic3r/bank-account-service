package bank.account.interceptors

trait AccountError {
  final case class AccountCreateException(msg: String, cause: Option[Throwable] = None)
    extends Exception(msg, cause.orNull)
 
  final case class AccountFillException(msg: String, cause: Option[Throwable] = None)
    extends Exception(msg, cause.orNull)

  final case class AccountMoneyException(msg: String, cause: Option[Throwable] = None)
    extends Exception(msg, cause.orNull)

  final case class AccountDeleteException(msg: String, cause: Option[Throwable] = None)
    extends Exception(msg, cause.orNull)

}