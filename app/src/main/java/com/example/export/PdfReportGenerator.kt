package com.example.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.data.local.AppActivityEventEntity
import com.example.data.local.AppInventorySnapshotEntity
import com.example.data.local.CallLogEntity
import com.example.monitoring.DateTimeUtils
import java.io.OutputStream

data class PdfReportRequest(
  val deviceId: String,
  val deviceLabel: String,
  val isImportedSnapshot: Boolean,
  val includeInventory: Boolean,
  val includeEvents: Boolean,
  val includeCallLogs: Boolean,
  val dateFilterStart: String? = null,
  val dateFilterEnd: String? = null,
)

object PdfReportGenerator {

  fun generatePdfReport(
    request: PdfReportRequest,
    inventory: List<AppInventorySnapshotEntity>,
    events: List<AppActivityEventEntity>,
    callLogs: List<CallLogEntity>,
    outputStream: OutputStream,
  ): Int {
    val pdf = PdfDocument()
    val pageWidth = 595 // A4 points
    val pageHeight = 842
    val margin = 36f
    val lineHeight = 16f

    val titlePaint =
      Paint().apply {
        color = Color.rgb(15, 30, 54)
        textSize = 18f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
      }
    val headingPaint =
      Paint().apply {
        color = Color.rgb(15, 118, 110)
        textSize = 13f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
      }
    val bodyPaint =
      Paint().apply {
        color = Color.rgb(30, 41, 59)
        textSize = 9.5f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
      }
    val monoPaint =
      Paint().apply {
        color = Color.rgb(71, 85, 105)
        textSize = 8.5f
        typeface = Typeface.MONOSPACE
      }
    val dividerPaint =
      Paint().apply {
        color = Color.rgb(203, 213, 225)
        strokeWidth = 1f
      }

    var pageNumber = 1
    var currentPage = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
    var canvas = currentPage.canvas
    var y = margin + 12f

    fun ensureSpace(requiredHeight: Float) {
      if (y + requiredHeight > pageHeight - margin) {
        canvas.drawText("Page $pageNumber", pageWidth - margin - 45f, pageHeight - 18f, monoPaint)
        pdf.finishPage(currentPage)
        pageNumber += 1
        currentPage = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        canvas = currentPage.canvas
        y = margin + 12f
      }
    }

    fun drawSectionTitle(title: String) {
      ensureSpace(34f)
      y += 10f
      canvas.drawText(title, margin, y, headingPaint)
      y += 6f
      canvas.drawLine(margin, y, pageWidth - margin, y, dividerPaint)
      y += 14f
    }

    // Header
    canvas.drawText("Personal Device Activity Report", margin, y, titlePaint)
    y += 20f
    canvas.drawText(
      "Device: ${request.deviceLabel} (${request.deviceId})",
      margin,
      y,
      bodyPaint,
    )
    y += 14f
    canvas.drawText(
      "Record Source: ${if (request.isImportedSnapshot) "Imported Paired Device Snapshot" else "Live Local Device Database"}",
      margin,
      y,
      bodyPaint,
    )
    y += 14f
    canvas.drawText(
      "Generated At: ${DateTimeUtils.formatDateTime(System.currentTimeMillis())}",
      margin,
      y,
      monoPaint,
    )
    y += 16f
    canvas.drawLine(margin, y, pageWidth - margin, y, dividerPaint)
    y += 16f

    // Summary stats
    val installedCount = inventory.count { it.isInstalled }
    val installEventsCount = events.count { it.eventType == "INSTALLED" }
    val uninstallEventsCount = events.count { it.eventType == "UNINSTALLED" }
    canvas.drawText(
      "Summary: Inventory=$installedCount installed (${inventory.size} tracked) | Installs=$installEventsCount | Uninstalls=$uninstallEventsCount | Calls=${callLogs.size}",
      margin,
      y,
      bodyPaint,
    )
    y += 16f

    if (request.includeEvents) {
      drawSectionTitle("Application Activity Events (${events.size})")
      if (events.isEmpty()) {
        ensureSpace(lineHeight)
        canvas.drawText("No recorded application activity events.", margin, y, bodyPaint)
        y += lineHeight
      } else {
        for (ev in events) {
          ensureSpace(lineHeight * 2)
          val line1 = "${ev.eventDate} ${ev.eventTime}  [${ev.eventType}]  ${ev.appName.take(32)}"
          val line2 = "  pkg=${ev.packageName} | ver=${ev.versionName} | source=${ev.installerLabel}"
          canvas.drawText(line1, margin, y, bodyPaint)
          y += 12f
          canvas.drawText(line2.take(92), margin, y, monoPaint)
          y += 14f
        }
      }
    }

    if (request.includeCallLogs) {
      drawSectionTitle("Call Log Metadata (${callLogs.size})")
      if (callLogs.isEmpty()) {
        ensureSpace(lineHeight)
        canvas.drawText("No recorded call log entries.", margin, y, bodyPaint)
        y += lineHeight
      } else {
        for (log in callLogs) {
          ensureSpace(lineHeight)
          val line =
            "${log.callDate} ${log.callTime} | ${log.callType.padEnd(9)} | ${log.phoneNumber.take(20).padEnd(20)} | ${DateTimeUtils.formatDuration(log.durationSeconds)}"
          canvas.drawText(line, margin, y, monoPaint)
          y += 14f
        }
      }
    }

    if (request.includeInventory) {
      drawSectionTitle("Installed Application Inventory (${inventory.size})")
      if (inventory.isEmpty()) {
        ensureSpace(lineHeight)
        canvas.drawText("No application inventory records.", margin, y, bodyPaint)
        y += lineHeight
      } else {
        for (app in inventory) {
          ensureSpace(lineHeight * 2)
          val status = if (app.isInstalled) "Installed" else "Uninstalled"
          val line1 = "${app.appName.take(36)} ($status) - v${app.versionName}"
          val line2 = "  ${app.packageName} | ${app.installDate} ${app.installTime} | ${app.installerLabel}"
          canvas.drawText(line1, margin, y, bodyPaint)
          y += 12f
          canvas.drawText(line2.take(92), margin, y, monoPaint)
          y += 14f
        }
      }
    }

    canvas.drawText("Page $pageNumber", pageWidth - margin - 45f, pageHeight - 18f, monoPaint)
    pdf.finishPage(currentPage)

    outputStream.use { pdf.writeTo(it) }
    pdf.close()
    return pageNumber
  }
}
