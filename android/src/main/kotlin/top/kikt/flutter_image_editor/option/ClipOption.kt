package top.kikt.flutter_image_editor.option

data class ClipOption(val x: Int, val y: Int, val width: Int, val height: Int) : Option

data class ClipRelativeOption(val x: Int, val y: Int, val width: Int,val height: Int,
                              val scale: Int): Option