package bank.account

import io.circe.generic.auto._
import io.finch.{Endpoint, _}
import io.finch.circe._
import io.finch.syntax._

object Routes {

  import bank.account.model.{AccountTransaction, AccountFillRequest}
  import bank.account.controller.AccountController
  private lazy val accountController = AccountController()

  final val balanceAccount: Endpoint[Option[AccountTransaction]] =
    get("balance" :: path[String]) {req: String => 
      for {
        r <- accountController.balanceAccount(req)
      } yield Ok(r)
    }

  final val fillAccount: Endpoint[AccountTransaction] =
    post("account" :: jsonBody[AccountFillRequest]) {req: String => 
      for {
        r <- accountController.fillAccount(req.uuid, req.amount)
      } yield r match {
        case Right(a) => Ok(a)
        case Left(m) => BadRequest(m)
      }
    }
}