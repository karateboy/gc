package models
import play.api._
import java.nio.file.{ Paths, Files, StandardOpenOption }
import play.api.Play.current
import scala.concurrent.ExecutionContext.Implicits.global
import com.github.nscala_time.time.Imports._
object Exporter {
  val activeMonitorType = List(MonitorType.mtvList: _*)
  def exportActiveMonitorType = {
    val path = Paths.get(current.path.getAbsolutePath + "/export/activeMonitor.txt")
    val monitorTypeStrList = activeMonitorType map { MonitorType.map(_)._id }
    val ret = monitorTypeStrList.fold("")((a, b) => {
      if (a.isEmpty())
        b
      else
        a + "\r" + b
    }).getBytes
    Files.write(path, ret, StandardOpenOption.CREATE)
  }

  var latestDateTime = new DateTime(0)

  def exportRealtimeData = {
    val path = Paths.get(current.path.getAbsolutePath + "/export/realtime.txt")
    var buffer = ""
    buffer += s"Selector,${Selector.get}\r"
    val latestRecord = Record.getLatestRecordListFuture(Record.MinCollection)(1)

    for (records <- latestRecord) yield {
      val data =
        if (records.isEmpty) {
          import org.mongodb.scala.bson._
          val mtRecordList = MonitorType.mtvList map { mt => Record.MtRecord(MonitorType.map(mt).desp, 0, MonitorStatus.NormalStat, "") }
          Record.RecordList("-", DateTime.now().getMillis, mtRecordList, new ObjectId())
        } else {
          records.head
        }
      val dateTime = new DateTime(data.time)
      if (latestDateTime < dateTime) {
        latestDateTime = dateTime

        buffer += s"InjectionDate, ${data.time}\r"
        val mtStrs = data.mtDataList map { mt_data => s"${mt_data.mtName}, ${mt_data.value}" }
        val mtDataStr = mtStrs.foldLeft("")((a, b) => {
          if (a.length() == 0)
            b
          else
            a + "\r" + b
        })
        buffer += mtDataStr
        Files.write(path, buffer.getBytes, StandardOpenOption.CREATE)
        true
      } else
        false
    }
  }
}