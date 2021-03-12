package top.kikt.flutter_image_editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapFactory.Options
import androidx.exifinterface.media.ExifInterface
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import top.kikt.flutter_image_editor.common.font.FontUtils
import top.kikt.flutter_image_editor.core.ImageHandler
import top.kikt.flutter_image_editor.core.ImageMerger
import top.kikt.flutter_image_editor.core.ResultHandler
import top.kikt.flutter_image_editor.error.BitmapDecodeException
import top.kikt.flutter_image_editor.option.*
import top.kikt.flutter_image_editor.util.ConvertUtils
import java.io.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class FlutterImageEditorPlugin(private val registrar: Registrar) : MethodCallHandler {
  companion object {
    @JvmStatic
    fun registerWith(registrar: Registrar) {
      val channel = MethodChannel(registrar.messenger(), "top.kikt/flutter_image_editor")
      channel.setMethodCallHandler(FlutterImageEditorPlugin(registrar))
    }

    val threadPool: ExecutorService = Executors.newCachedThreadPool()

    inline fun runOnBackground(crossinline block: () -> Unit) {
      threadPool.execute {
        block()
      }
    }
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    val resultHandler = ResultHandler(result)
    runOnBackground {
      try {
        when (call.method) {
          "memoryToFile" -> {
            handle(call, resultHandler, false)
          }
          "memoryToMemory" -> {
            handle(call, resultHandler, true)
          }
          "fileToMemory" -> {
            handle(call, resultHandler, true)
          }
          "fileToFile" -> {
            handle(call, resultHandler, false)
          }
          "getCachePath" -> {
            val cachePath = registrar.activeContext().cacheDir.absolutePath
            resultHandler.reply(cachePath)
          }
          "mergeToMemory" -> {
            handleMerge(call, resultHandler, true)
          }
          "mergeToFile" -> {
            handleMerge(call, resultHandler, false)
          }
          "registerFont" -> {
            val fontPath = call.argument<String>("path")!!
            val name = FontUtils.registerFont(fontPath)
            resultHandler.reply(name)
          }
          else -> {
            resultHandler.notImplemented()
          }
        }
      } catch (e: BitmapDecodeException) {
        resultHandler.replyError("decode bitmap error")
      } catch (e: Exception) {
        val writer = StringWriter()
        val printWriter = PrintWriter(writer)
        printWriter.use {
          e.printStackTrace(printWriter)
          resultHandler.replyError(writer.buffer.toString(), "", null)

        }
      }
    }
  }

  private fun handleMerge(call: MethodCall, resultHandler: ResultHandler, memory: Boolean) {
    val mergeOptionMap = call.argument<Any>("option") as Map<*, *>
    val mergeOption = MergeOption(mergeOptionMap)
    val imageMerger = ImageMerger(mergeOption)
    val byteArray = imageMerger.process()

    if (byteArray == null) {
      resultHandler.replyError("cannot merge image")
      return;
    }

    if (memory) {
      resultHandler.reply(byteArray)
    } else {
      val extName = if (mergeOption.formatOption.format == 1) "jpg" else "png"
      val f = File(registrar.context().cacheDir, "${System.currentTimeMillis()}.$extName")
      f.writeBytes(byteArray)
      resultHandler.reply(byteArray)
    }
  }

  private fun MethodCall.getSrc(): String? {
    return this.argument<String>("src")
  }

  private fun MethodCall.getTarget(): String? {
    return this.argument<String>("target")
  }

  private fun MethodCall.getOptions(bitmapWrapper: BitmapWrapper): List<Option> {
    val optionMap = this.argument<List<Any>>("options")!!
    return ConvertUtils.convertMapOption(optionMap, bitmapWrapper)
  }

  private fun MethodCall.getMemory(): ByteArray? {
    return this.argument<ByteArray>("image")
  }

  private fun MethodCall.getBitmap(): BitmapWrapper {
    val src = getSrc()

    if (src != null) {
      val options = BitmapFactory.Options()
      options.inJustDecodeBounds = true
      BitmapFactory.decodeFile(src, options)
      val width = options.outWidth;
      val height = options.outHeight;
      options.inSampleSize = calculateInSampleSize(options, 700, 700)
      options.inJustDecodeBounds = false
      val bitmap = BitmapFactory.decodeFile(src, options)
      val exifInterface = ExifInterface(src)
      return wrapperBitmapWrapper(bitmap, exifInterface, options.inSampleSize)
    }

    val memory = getMemory()
    if (memory != null) {
      val options = BitmapFactory.Options()
      options.inJustDecodeBounds = true
      BitmapFactory.decodeByteArray(memory, 0, memory.count(), options)
      val width = options.outWidth;
      val height = options.outHeight;
      options.inSampleSize = calculateInSampleSize(options, 700, 700)
      options.inJustDecodeBounds = false
      val bitmap = BitmapFactory.decodeByteArray(memory, 0, memory.count(), options)

      val exifInterface = ExifInterface(ByteArrayInputStream(memory))
      return wrapperBitmapWrapper(bitmap, exifInterface, options.inSampleSize)
    }

    throw BitmapDecodeException()
  }

  private fun decodeSampledBitmapFromByteArray(byteArray: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap {
    // First decode with inJustDecodeBounds=true to check dimensions
    val options = Options()
    options.inJustDecodeBounds = true
    BitmapFactory.decodeByteArray(byteArray, 0, byteArray.count(), options)
    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeByteArray(byteArray, 0, byteArray.count(), options)
  }

  private fun decodeSampledBitmapFromResource(path: String?, reqWidth: Int, reqHeight: Int): Bitmap {
    // First decode with inJustDecodeBounds=true to check dimensions
    val options = Options()
    options.inJustDecodeBounds = true
    BitmapFactory.decodeFile(path, options)
    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeFile(path, options)
  }

  private fun calculateInSampleSize(options: Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
      val halfHeight = height / 2
      val halfWidth = width / 2

      // Calculate the largest inSampleSize value that is a power of 2 and keeps both
      // height and width larger than the requested height and width.
      while (halfHeight / inSampleSize > reqHeight
              && halfWidth / inSampleSize > reqWidth) {
        inSampleSize *= 2
      }
    }
    return inSampleSize
  }

  private fun wrapperBitmapWrapper(bitmap: Bitmap, exifInterface: ExifInterface, scale: Int): BitmapWrapper {
    var degree = 0
    var flipOption = FlipOption(horizontal = false)

    when (exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
      ExifInterface.ORIENTATION_NORMAL -> {
        degree = 0
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> {
        degree = 90
      }
      ExifInterface.ORIENTATION_ROTATE_180 -> {
        degree = 180
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> {
        degree = 270
      }
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        flipOption = FlipOption(horizontal = true)
      }
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        flipOption = FlipOption(vertical = true)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        degree = 90
        flipOption = FlipOption(horizontal = true)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        degree = 270
        flipOption = FlipOption(horizontal = true)
      }
    }

    return BitmapWrapper(bitmap, degree, flipOption, scale)

  }


  private fun MethodCall.getFormatOption(): FormatOption {
    return ConvertUtils.getFormatOption(this)
  }


  private fun handle(imageHandler: ImageHandler, formatOption: FormatOption, outputMemory: Boolean, resultHandler: ResultHandler, targetPath: String? = null) {
    if (outputMemory) {
      val byteArray = imageHandler.outputByteArray(formatOption)
      resultHandler.reply(byteArray)
    } else {
      if (targetPath == null) {
        resultHandler.reply(null)
      } else {
        imageHandler.outputToFile(targetPath, formatOption)
        resultHandler.reply(targetPath)
      }
    }
  }

  private fun handle(call: MethodCall, resultHandler: ResultHandler, outputMemory: Boolean) {
    val bitmapWrapper = call.getBitmap()
    val imageHandler = ImageHandler(registrar.context(), bitmapWrapper.bitmap)
    imageHandler.handle(call.getOptions(bitmapWrapper))
    handle(imageHandler, call.getFormatOption(), outputMemory, resultHandler, call.getTarget())
    bitmapWrapper.bitmap.recycle()
  }
}

data class BitmapWrapper(val bitmap: Bitmap, val degree: Int, val flipOption: FlipOption, val scale: Int)