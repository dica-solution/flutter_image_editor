package top.kikt.flutter_image_editor.core

import android.content.Context
import android.graphics.*
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import top.kikt.flutter_image_editor.common.font.FontUtils
import top.kikt.flutter_image_editor.option.*
import top.kikt.flutter_image_editor.option.draw.DrawOption
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.min
import kotlin.math.roundToInt


/// create 2019-10-08 by cai

class ImageHandler(private val context: Context, private var bitmap: Bitmap) {

  fun handle(options: List<Option>) {
    var newBitmap: Bitmap = bitmap;
    for (option in options) {
      when (option) {
        is FlipOption -> newBitmap = handleFlip(option)
        is ClipOption -> newBitmap = handleClip(option)
        is ClipRelativeOption -> newBitmap = handleRelativeClip(option)
        is RotateOption -> newBitmap = handleRotate(option)
        is ColorOption -> newBitmap = handleColor(option)
        is ScaleOption -> newBitmap = handleScale(option)
        is AddTextOpt -> newBitmap = handleText(option)
        is MixImageOpt -> newBitmap = handleMixImage(option)
        is DrawOption -> newBitmap = bitmap.draw(option)
      }
      if (bitmap != newBitmap) {
        bitmap.recycle()
        bitmap = newBitmap
      }
    }
  }

  private fun handleMixImage(option: MixImageOpt): Bitmap {
    val newBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
    val canvas = Canvas(newBitmap)
    canvas.drawBitmap(bitmap, 0F, 0F, null)

    val src = BitmapFactory.decodeByteArray(option.img, 0, option.img.count())

    val paint = Paint()
    paint.xfermode = PorterDuffXfermode(option.porterDuffMode)

    val dstRect = Rect(option.x, option.y, option.x + option.w, option.y + option.h)
    canvas.drawBitmap(src, null, dstRect, paint)

    return newBitmap
  }

  private fun handleScale(option: ScaleOption): Bitmap {
    val w = min(bitmap.width, option.width)
    val h = min(bitmap.height, option.height)

    val newBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    val canvas = Canvas(newBitmap)

    val p = Paint()

    val m = Matrix()

    val width: Int = bitmap.width
    val height: Int = bitmap.height
    if (width != w || height != h) {
      val sx: Float = w / width.toFloat()
      val sy: Float = h / height.toFloat()
      m.setScale(sx, sy)
    }

    canvas.drawBitmap(bitmap, m, p)

    return newBitmap
  }

  private fun handleRotate(option: RotateOption): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(option.angle.toFloat())
    return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.getWidth(),
            bitmap.getHeight(),
            matrix,
            true
    )
  }

  private fun handleFlip(option: FlipOption): Bitmap {
    val tmpBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(tmpBitmap)
    val matrix = Matrix().apply {
      val x = if (option.horizontal) -1F else 1F
      val y = if (option.vertical) -1F else 1F
      postScale(x, y)
    }

    val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    canvas.drawBitmap(out, matrix, null)
    return out
  }

  private fun handleClip(option: ClipOption): Bitmap {
    val x = option.x
    val y = option.y
    return Bitmap.createBitmap(bitmap, x, y, option.width, option.height, null, false)
  }

  private fun handleRelativeClip(option: ClipRelativeOption): Bitmap {
    val x = option.x
    val y = option.y
    val width = bitmap.width
    val height = bitmap.height
    return Bitmap.createBitmap(bitmap, (x*width).roundToInt(), (y*height).roundToInt(), (option.width*width).roundToInt(), (option.height*height).roundToInt(), null, false)
  }

  private fun handleColor(option: ColorOption): Bitmap {
    val newBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)

    val canvas = Canvas(newBitmap)

    val paint = Paint()
    paint.colorFilter = ColorMatrixColorFilter(option.matrix)

    canvas.drawBitmap(bitmap, 0F, 0F, paint)

    return newBitmap
  }

  private fun handleText(option: AddTextOpt): Bitmap {
    val newBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)

    val canvas = Canvas(newBitmap)
    val paint = Paint()

    canvas.drawBitmap(bitmap, 0F, 0F, paint)

    for (text in option.texts) {
      drawText(text, canvas)
    }

    return newBitmap
  }

  private fun drawText(text: Text, canvas: Canvas) {
    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    textPaint.color = Color.argb(text.a, text.r, text.g, text.b)
    textPaint.textSize = text.fontSizePx.toFloat()

    if (text.fontName.isNotEmpty()) {
      try {
        val typefaceFromAsset = FontUtils.getFont(text.fontName)
        textPaint.typeface = typefaceFromAsset
      } catch (e: Exception) {
      }
    }
//    canvas.drawText(text.text, text.x.toFloat(), text.y.toFloat(), textPaint)

    val staticLayout = getStaticLayout(text, textPaint, canvas.width - text.x)

    canvas.translate(text.x.toFloat(), text.y.toFloat())
    staticLayout.draw(canvas)
    canvas.translate((-text.x).toFloat(), (-text.y).toFloat())
  }

  @Suppress("DEPRECATION")
  private fun getStaticLayout(text: Text, textPaint: TextPaint, width: Int): StaticLayout {
    return if (Build.VERSION.SDK_INT >= 23) {
      StaticLayout.Builder.obtain(
              text.text, 0, text.text.length, textPaint, width
      ).build()
    } else {
      StaticLayout(text.text, textPaint, width, Layout.Alignment.ALIGN_NORMAL, 1.0F, 0.0F, true)
    }
  }

  fun outputToFile(dstPath: String, formatOption: FormatOption) {
    val outputStream = FileOutputStream(dstPath)
    output(outputStream, formatOption)
  }

  fun outputByteArray(formatOption: FormatOption): ByteArray {
    val outputStream = ByteArrayOutputStream()
    output(outputStream, formatOption)
    return outputStream.toByteArray()
  }

  private fun output(outputStream: OutputStream, formatOption: FormatOption) {
    outputStream.use {
      if (formatOption.format == 0) {
        bitmap.compress(Bitmap.CompressFormat.PNG, formatOption.quality, outputStream)
      } else {
        bitmap.compress(Bitmap.CompressFormat.JPEG, formatOption.quality, outputStream)
      }
    }
  }
}