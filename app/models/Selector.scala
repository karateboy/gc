package models

object Selector {
  private var current: Int = 1

  def get = current
  def set(v: Int) = { current = v }
}