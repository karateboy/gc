package controllers

object OutputType extends Enumeration {
  val html: OutputType.Value = Value("html")
  val pdf: OutputType.Value = Value("pdf")
  val excel: OutputType.Value = Value("excel")
  val json: OutputType.Value = Value("json")
}