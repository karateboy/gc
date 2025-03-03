package models

import play.api.libs.json.{Json, OWrites, Reads}

case class TargetRange(mtName:String, target:Option[Double] = None, low:Option[Double] = None, high:Option[Double] = None)
case class CalibrationTarget(targets:Seq[TargetRange])

object CalibrationTarget {
  implicit val targetRangeWrite: OWrites[TargetRange] = Json.writes[TargetRange]
  implicit val targetRangeRead: Reads[TargetRange] = Json.reads[TargetRange]
  implicit val calibrationTargetWrite: OWrites[CalibrationTarget] = Json.writes[CalibrationTarget]
  implicit val calibrationTargetRead: Reads[CalibrationTarget] = Json.reads[CalibrationTarget]
  val defaultCalibrationTarget: CalibrationTarget =
    CalibrationTarget(
      Seq(
        TargetRange("H2"),
        TargetRange("ArO2"),
        TargetRange("N2"),
        TargetRange("CO"),
        TargetRange("CH4"),
        TargetRange("CO2")))
}