package models

case class GroupInfo(id: String, name: String, privilege: String)
object Group extends Enumeration {

  def mapEntry(_id: String, name: String, privilege: String) =
    _id -> GroupInfo(_id.toString, name, privilege)

  val adminID = "admin"
  val adminGroup = mapEntry(adminID, "系統管理員", "")
  
  val map = Map(
    adminGroup)

  val getInfoList = map.values.toList

  def getGroupInfo(id: String) = {
    map.getOrElse(id, adminGroup._2)
  }
}