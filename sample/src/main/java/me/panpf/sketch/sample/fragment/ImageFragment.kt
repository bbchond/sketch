package me.panpf.sketch.sample.fragment

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.text.format.Formatter
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import me.panpf.sketch.Sketch
import me.panpf.sketch.datasource.DataSource
import me.panpf.sketch.display.FadeInImageDisplayer
import me.panpf.sketch.decode.ImageAttrs
import me.panpf.sketch.drawable.SketchDrawable
import me.panpf.sketch.drawable.SketchGifDrawable
import me.panpf.sketch.drawable.SketchRefBitmap
import me.panpf.sketch.request.*
import me.panpf.sketch.state.MemoryCacheStateImage
import me.panpf.sketch.uri.FileUriModel
import me.panpf.sketch.uri.GetDataSourceException
import me.panpf.sketch.uri.UriModel
import me.panpf.sketch.util.SketchUtils
import me.panpf.sketch.zoom.ImageZoomer
import me.panpf.sketch.zoom.Size
import me.panpf.sketch.sample.BaseFragment
import me.panpf.sketch.sample.BindContentView
import me.panpf.sketch.sample.R
import me.panpf.sketch.sample.activity.PageBackgApplyCallback
import me.panpf.sketch.sample.bean.Image
import me.panpf.sketch.sample.event.AppConfigChangedEvent
import me.panpf.sketch.sample.util.AppConfig
import me.panpf.sketch.sample.util.ApplyWallpaperAsyncTask
import me.panpf.sketch.sample.util.SaveImageAsyncTask
import me.panpf.sketch.sample.widget.HintView
import me.panpf.sketch.sample.widget.MappingView
import me.panpf.sketch.sample.widget.SampleImageView
import me.panpf.sketch.sample.bindView
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import java.io.File
import java.io.IOException
import java.util.*

@BindContentView(R.layout.fragment_image)
class ImageFragment : BaseFragment() {

    private val imageView: SampleImageView by bindView(R.id.image_imageFragment_image)
    private val mappingView: MappingView by bindView(R.id.mapping_imageFragment)
    private val hintView: HintView by bindView(R.id.hint_imageFragment_hint)

    private lateinit var image: Image
    private var loadingImageOptionsKey: String? = null
    private var showTools: Boolean = false

    private lateinit var finalShowImageUrl: String

    private val showHelper = ShowHelper()
    private val zoomHelper = ZoomHelper()
    private var mappingHelper = MappingHelper()
    private val clickHelper = ClickHelper()
    private val setWindowBackgroundHelper = SetWindowBackgroundHelper()
    private val gifPlayFollowPageVisibleHelper = GifPlayFollowPageVisibleHelper()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setWindowBackgroundHelper.onCreate(activity)

        arguments?.let {
            image = it.getParcelable(PARAM_REQUIRED_STRING_IMAGE_URI)
            loadingImageOptionsKey = it.getString(PARAM_REQUIRED_STRING_LOADING_IMAGE_OPTIONS_KEY)
            showTools = it.getBoolean(PARAM_REQUIRED_BOOLEAN_SHOW_TOOLS)
        }

        val showHighDefinitionImage = AppConfig.getBoolean(context, AppConfig.Key.SHOW_UNSPLASH_RAW_IMAGE)
        finalShowImageUrl = if (showHighDefinitionImage) image.rawQualityUrl else image.normalQualityUrl
    }

    override fun onViewCreated(view: View?, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        zoomHelper.onViewCreated()
        mappingHelper.onViewCreated()
        clickHelper.onViewCreated()
        showHelper.onViewCreated()

        EventBus.getDefault().register(this)
    }

    override fun onUserVisibleChanged(isVisibleToUser: Boolean) {
        zoomHelper.onUserVisibleChanged()
        setWindowBackgroundHelper.onUserVisibleChanged()
        gifPlayFollowPageVisibleHelper.onUserVisibleChanged()
    }

    override fun onDestroyView() {
        EventBus.getDefault().unregister(this)
        super.onDestroyView()
    }

    @Suppress("unused")
    @Subscribe
    fun onEvent(event: AppConfigChangedEvent) {
        if (AppConfig.Key.SUPPORT_ZOOM == event.key) {
            zoomHelper.onConfigChanged()
            mappingHelper.onViewCreated()
        } else if (AppConfig.Key.READ_MODE == event.key) {
            zoomHelper.onReadModeConfigChanged()
        }
    }

    class PlayImageEvent

    private inner class ShowHelper {
        fun onViewCreated() {
            imageView.displayListener = ImageDisplayListener()
            imageView.downloadProgressListener = ImageDownloadProgressListener()

            initOptions()
            imageView.displayImage(finalShowImageUrl)
        }

        private fun initOptions() {
            imageView.page = SampleImageView.Page.DETAIL

            val options = imageView.options

            // 允许播放 GIF
            options.isDecodeGifImage = true

            // 有占位图选项信息的话就使用内存缓存占位图但不使用任何显示器，否则就是用渐入显示器
            if (!TextUtils.isEmpty(loadingImageOptionsKey)) {
                val uriModel = UriModel.match(activity, finalShowImageUrl)
                var cachedRefBitmap: SketchRefBitmap? = null
                var memoryCacheKey: String? = null
                if (uriModel != null) {
                    memoryCacheKey = SketchUtils.makeRequestKey(image.normalQualityUrl, uriModel, loadingImageOptionsKey!!)
                    cachedRefBitmap = Sketch.with(activity).configuration.memoryCache.get(memoryCacheKey)
                }
                if (cachedRefBitmap != null) {
                    options.loadingImage = MemoryCacheStateImage(memoryCacheKey, null)
                } else {
                    options.displayer = FadeInImageDisplayer()
                }
            } else {
                options.displayer = FadeInImageDisplayer()
            }
        }

        inner class ImageDisplayListener : DisplayListener {
            override fun onStarted() {
                hintView.loading(null)
            }

            override fun onCompleted(drawable: Drawable, imageFrom: ImageFrom, imageAttrs: ImageAttrs) {
                hintView.hidden()

                setWindowBackgroundHelper.onDisplayCompleted()
                gifPlayFollowPageVisibleHelper.onDisplayCompleted()
            }

            override fun onError(cause: ErrorCause) {
                hintView.hint(R.drawable.ic_error, "Image display failed", "Again", View.OnClickListener { imageView.displayImage(finalShowImageUrl) })
            }

            override fun onCanceled(cause: CancelCause) {
                @Suppress("NON_EXHAUSTIVE_WHEN")
                when (cause) {
                    CancelCause.PAUSE_DOWNLOAD -> hintView.hint(R.drawable.ic_error, "Pause to download new image for saving traffic", "I do not care", View.OnClickListener {
                        val requestLevel = imageView.options.requestLevel
                        imageView.options.requestLevel = RequestLevel.NET
                        imageView.displayImage(finalShowImageUrl)
                        imageView.options.requestLevel = requestLevel
                    })
                    CancelCause.PAUSE_LOAD -> hintView.hint(R.drawable.ic_error, "Paused to load new image", "Forced to load", View.OnClickListener {
                        val requestLevel = imageView.options.requestLevel
                        imageView.options.requestLevel = RequestLevel.NET
                        imageView.displayImage(finalShowImageUrl)
                        imageView.options.requestLevel = requestLevel
                    })
                }
            }
        }

        inner class ImageDownloadProgressListener : DownloadProgressListener {

            override fun onUpdateDownloadProgress(totalLength: Int, completedLength: Int) {
                hintView.setProgress(totalLength, completedLength)
            }
        }
    }

    private inner class ZoomHelper {
        fun onViewCreated() {
            imageView.isZoomEnabled = AppConfig.getBoolean(imageView.context, AppConfig.Key.SUPPORT_ZOOM)

            onReadModeConfigChanged()   // 初始化阅读模式
            onUserVisibleChanged()  // 初始化超大图查看器的暂停状态，这一步很重要
        }

        fun onConfigChanged() {
            onViewCreated()
        }

        fun onUserVisibleChanged() {
            imageView.zoomer?.let {
                if (AppConfig.getBoolean(activity, AppConfig.Key.PAUSE_BLOCK_DISPLAY_WHEN_PAGE_NOT_VISIBLE)) {
                    it.blockDisplayer.setPause(!isVisibleToUser)  // 不可见的时候暂停超大图查看器，节省内存
                } else if (isVisibleToUser && it.blockDisplayer.isPaused) {
                    it.blockDisplayer.setPause(false) // 因为有 PAUSE_BLOCK_DISPLAY_WHEN_PAGE_NOT_VISIBLE 开关的存在，可能会出现关闭开关后依然处于暂停状态的情况，因此这里恢复一下，加个保险
                }
            }
        }

        fun onReadModeConfigChanged() {
            imageView.zoomer?.let {
                it.isReadMode = AppConfig.getBoolean(activity, AppConfig.Key.READ_MODE)
            }
        }
    }

    private inner class MappingHelper {
        val zoomMatrixChangedListener = ZoomMatrixChangedListener()

        fun onViewCreated() {
            if (!showTools) {
                mappingView.visibility = View.GONE
                return
            }

            if (imageView.zoomer != null) {
                // MappingView 跟随 Matrix 变化刷新显示区域
                imageView.zoomer?.addOnMatrixChangeListener(zoomMatrixChangedListener)

                // MappingView 跟随碎片变化刷新碎片区域
                imageView.zoomer?.blockDisplayer?.setOnBlockChangedListener { mappingView.blockChanged(it) }

                // 点击 MappingView 定位到指定位置
                mappingView.setOnSingleClickListener(object : MappingView.OnSingleClickListener {
                    override fun onSingleClick(x: Float, y: Float): Boolean {
                        val drawable = imageView.drawable ?: return false

                        if (drawable.intrinsicWidth == 0 || drawable.intrinsicHeight == 0) {
                            return false
                        }

                        if (mappingView.width == 0 || mappingView.height == 0) {
                            return false
                        }

                        val widthScale = drawable.intrinsicWidth.toFloat() / mappingView.width
                        val heightScale = drawable.intrinsicHeight.toFloat() / mappingView.height
                        val realX = x * widthScale
                        val realY = y * heightScale

                        val showLocationAnimation = AppConfig.getBoolean(imageView.context, AppConfig.Key.LOCATION_ANIMATE)
                        location(realX, realY, showLocationAnimation)
                        return true
                    }
                })
            } else {
                mappingView.setOnSingleClickListener(null)
                mappingView.update(Size(0, 0), Rect())
            }

            mappingView.options.displayer = FadeInImageDisplayer()
            mappingView.options.setMaxSize(600, 600)
            mappingView.displayImage(finalShowImageUrl)
        }

        fun location(x: Float, y: Float, animate: Boolean): Boolean {
            imageView.zoomer?.location(x, y, animate)
            return true
        }

        private inner class ZoomMatrixChangedListener : ImageZoomer.OnMatrixChangeListener {
            internal var visibleRect = Rect()

            override fun onMatrixChanged(imageZoomer: ImageZoomer) {
                imageZoomer.getVisibleRect(visibleRect)
                mappingView.update(imageZoomer.drawableSize, visibleRect)
            }
        }
    }

    private inner class SetWindowBackgroundHelper {
        private var pageBackgApplyCallback: PageBackgApplyCallback? = null

        fun onCreate(activity: Activity) {
            if (activity is PageBackgApplyCallback) {
                setWindowBackgroundHelper.pageBackgApplyCallback = activity
            }
        }

        fun onUserVisibleChanged() {
            if (pageBackgApplyCallback != null && isVisibleToUser) {
                pageBackgApplyCallback!!.onApplyBackground(finalShowImageUrl)
            }
        }

        fun onDisplayCompleted() {
            onUserVisibleChanged()
        }
    }

    private inner class GifPlayFollowPageVisibleHelper {
        fun onUserVisibleChanged() {
            val drawable = imageView.drawable
            val lastDrawable = SketchUtils.getLastDrawable(drawable)
            if (lastDrawable != null && lastDrawable is SketchGifDrawable) {
                (lastDrawable as SketchGifDrawable).followPageVisible(isVisibleToUser, false)
            }
        }

        fun onDisplayCompleted() {
            val drawable = imageView.drawable
            val lastDrawable = SketchUtils.getLastDrawable(drawable)
            if (lastDrawable != null && lastDrawable is SketchGifDrawable) {
                (lastDrawable as SketchGifDrawable).followPageVisible(isVisibleToUser, true)
            }
        }
    }

    private inner class ClickHelper {

        fun onViewCreated() {
            // 将单击事件传递给上层 Activity
            imageView.onClickListener = View.OnClickListener { v ->
                val parentFragment = parentFragment
                if (parentFragment != null && parentFragment is ImageZoomer.OnViewTapListener) {
                    (parentFragment as ImageZoomer.OnViewTapListener).onViewTap(v, 0f, 0f)
                }
            }

            imageView.setOnLongClickListener {
                val menuItemList = LinkedList<MenuItem>()

                menuItemList.add(MenuItem(
                        "Image Info",
                        DialogInterface.OnClickListener { _, _ -> imageView.showInfo(activity) }
                ))
                menuItemList.add(MenuItem(
                        "Zoom/Rotate/Block Display",
                        DialogInterface.OnClickListener { _, _ -> showZoomMenu() }
                ))
                menuItemList.add(MenuItem(
                        String.format("Toggle ScaleType (%s)", imageView.zoomer?.scaleType ?: imageView.scaleType),
                        DialogInterface.OnClickListener { _, _ -> showScaleTypeMenu() }
                ))
                menuItemList.add(MenuItem(
                        "Auto Play",
                        DialogInterface.OnClickListener { _, _ -> play() }
                ))
                menuItemList.add(MenuItem(
                        "Set as wallpaper",
                        DialogInterface.OnClickListener { _, _ -> setWallpaper() }
                ))
                menuItemList.add(MenuItem(
                        "Share Image",
                        DialogInterface.OnClickListener { _, _ -> share() }
                ))
                menuItemList.add(MenuItem(
                        "Save Image",
                        DialogInterface.OnClickListener { _, _ -> save() }
                ))

                val items = arrayOfNulls<String>(menuItemList.size)
                var w = 0
                val size = menuItemList.size
                while (w < size) {
                    items[w] = menuItemList[w].title
                    w++
                }

                val itemClickListener = DialogInterface.OnClickListener { dialog, which ->
                    dialog.dismiss()
                    menuItemList[which].clickListener?.onClick(dialog, which)
                }

                AlertDialog.Builder(activity)
                        .setItems(items, itemClickListener)
                        .show()
                true
            }
        }

        fun showZoomMenu() {
            val menuItemList = LinkedList<MenuItem>()

            // 缩放信息
            val zoomer = imageView.zoomer
            if (zoomer != null) {
                val zoomInfoBuilder = StringBuilder()
                val zoomScale = SketchUtils.formatFloat(zoomer.zoomScale, 2)
                val visibleRect = Rect()
                zoomer.getVisibleRect(visibleRect)
                val visibleRectString = visibleRect.toShortString()
                zoomInfoBuilder.append("Zoom: ").append(zoomScale).append(" / ").append(visibleRectString)
                menuItemList.add(MenuItem(zoomInfoBuilder.toString(), null))
            } else {
                menuItemList.add(MenuItem("Zoom (Disabled)", null))
            }

            // 分块显示信息
            if (zoomer != null) {
                val blockDisplayer = zoomer.blockDisplayer
                val blockInfoBuilder = StringBuilder()
                when {
                    blockDisplayer.isReady -> {
                        blockInfoBuilder.append("Blocks：")
                                .append(blockDisplayer.blockBaseNumber)
                                .append("/")
                                .append(blockDisplayer.blockSize)
                                .append("/")
                                .append(Formatter.formatFileSize(context, blockDisplayer.allocationByteCount))

                        blockInfoBuilder.append("\n")
                        blockInfoBuilder.append("Blocks Area：").append(blockDisplayer.decodeRect.toShortString())

                        blockInfoBuilder.append("\n")
                        blockInfoBuilder.append("Blocks Area (SRC)：").append(blockDisplayer.decodeSrcRect.toShortString())
                    }
                    blockDisplayer.isInitializing -> {
                        blockInfoBuilder.append("\n")
                        blockInfoBuilder.append("Blocks initializing...")
                    }
                    else -> blockInfoBuilder.append("Blocks (No need)")
                }
                menuItemList.add(MenuItem(blockInfoBuilder.toString(), null))
            } else {
                menuItemList.add(MenuItem("Blocks (Disabled)", null))
            }

            // 分块边界开关
            if (zoomer != null) {
                val blockDisplayer = zoomer.blockDisplayer
                if (blockDisplayer.isReady || blockDisplayer.isInitializing) {
                    menuItemList.add(MenuItem(if (blockDisplayer.isShowBlockBounds) "Hide block bounds" else "Show block bounds",
                            DialogInterface.OnClickListener { _, _ -> imageView.zoomer?.blockDisplayer?.let { it.isShowBlockBounds = !it.isShowBlockBounds } }))
                } else {
                    menuItemList.add(MenuItem("Block bounds (No need)", null))
                }
            } else {
                menuItemList.add(MenuItem("Block bounds (Disabled)", null))
            }

            // 阅读模式开关
            if (zoomer != null) {
                menuItemList.add(MenuItem(if (zoomer.isReadMode) "Close read mode" else "Open read mode",
                        DialogInterface.OnClickListener { _, _ -> imageView.zoomer?.let { it.isReadMode = !it.isReadMode } }))
            } else {
                menuItemList.add(MenuItem("Read mode (Zoom disabled)", null))
            }

            // 旋转菜单
            if (zoomer != null) {
                menuItemList.add(MenuItem(String.format("Clockwise rotation 90°（%d）", zoomer.rotateDegrees),
                        DialogInterface.OnClickListener { _, _ ->
                            imageView.zoomer?.let {
                                if (!it.rotateBy(90)) {
                                    Toast.makeText(context, "The rotation angle must be a multiple of 90", Toast.LENGTH_LONG).show()
                                }
                            }
                        }))
            } else {
                menuItemList.add(MenuItem("Clockwise rotation 90° (Zoom disabled)", null))
            }

            val items = arrayOfNulls<String>(menuItemList.size)
            var w = 0
            val size = menuItemList.size
            while (w < size) {
                items[w] = menuItemList[w].title
                w++
            }

            val itemClickListener = DialogInterface.OnClickListener { dialog, which ->
                dialog.dismiss()
                menuItemList[which].clickListener?.onClick(dialog, which)
            }

            AlertDialog.Builder(activity)
                    .setItems(items, itemClickListener)
                    .show()
        }

        fun showScaleTypeMenu() {
            val builder = AlertDialog.Builder(activity)

            builder.setTitle("Toggle ScaleType")

            val items = arrayOfNulls<String>(7)
            items[0] = "CENTER"
            items[1] = "CENTER_CROP"
            items[2] = "CENTER_INSIDE"
            items[3] = "FIT_START"
            items[4] = "FIT_CENTER"
            items[5] = "FIT_END"
            items[6] = "FIT_XY"

            builder.setItems(items) { dialog, which ->
                dialog.dismiss()

                when (which) {
                    0 -> imageView.scaleType = ImageView.ScaleType.CENTER
                    1 -> imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                    2 -> imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    3 -> imageView.scaleType = ImageView.ScaleType.FIT_START
                    4 -> imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                    5 -> imageView.scaleType = ImageView.ScaleType.FIT_END
                    6 -> imageView.scaleType = ImageView.ScaleType.FIT_XY
                }
            }

            builder.setNegativeButton("Cancel", null)
            builder.show()
        }

        fun getImageFile(imageUri: String?): File? {
            if (TextUtils.isEmpty(imageUri)) {
                return null
            }

            val uriModel = UriModel.match(context, imageUri!!)
            if (uriModel == null) {
                Toast.makeText(activity, "Unknown format uri: " + imageUri, Toast.LENGTH_LONG).show()
                return null
            }

            val dataSource: DataSource
            try {
                dataSource = uriModel.getDataSource(context, imageUri, null)
            } catch (e: GetDataSourceException) {
                e.printStackTrace()
                Toast.makeText(activity, "The Image is not ready yet", Toast.LENGTH_LONG).show()
                return null
            }

            try {
                return dataSource.getFile(context.externalCacheDir, null)
            } catch (e: IOException) {
                e.printStackTrace()
                return null
            }

        }

        fun share() {
            val drawable = imageView.drawable
            val imageUri = if (drawable != null && drawable is SketchDrawable) (drawable as SketchDrawable).uri else null
            if (TextUtils.isEmpty(imageUri)) {
                Toast.makeText(activity, "Please wait later", Toast.LENGTH_LONG).show()
                return
            }

            val imageFile = getImageFile(imageUri)
            if (imageFile == null) {
                Toast.makeText(activity, "The Image is not ready yet", Toast.LENGTH_LONG).show()
                return
            }

            val intent = Intent(Intent.ACTION_SEND)
            intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(imageFile))
            intent.type = "image/" + parseFileType(imageFile.name)!!

            val infoList = activity.packageManager.queryIntentActivities(intent, 0)
            if (infoList == null || infoList.isEmpty()) {
                Toast.makeText(activity, "There is no APP on your device to share the picture", Toast.LENGTH_LONG).show()
                return
            }

            startActivity(intent)
        }

        fun setWallpaper() {
            val drawable = imageView.drawable
            val imageUri = if (drawable != null && drawable is SketchDrawable) (drawable as SketchDrawable).uri else null
            if (TextUtils.isEmpty(imageUri)) {
                Toast.makeText(activity, "Please wait later", Toast.LENGTH_LONG).show()
                return
            }

            val imageFile = getImageFile(imageUri)
            if (imageFile == null) {
                Toast.makeText(activity, "The Image is not ready yet", Toast.LENGTH_LONG).show()
                return
            }

            ApplyWallpaperAsyncTask(activity, imageFile).execute(0)
        }

        fun play() {
            EventBus.getDefault().post(PlayImageEvent())
        }

        fun save() {
            val drawable = imageView.drawable
            val imageUri = if (drawable != null && drawable is SketchDrawable) (drawable as SketchDrawable).uri else null
            if (TextUtils.isEmpty(imageUri)) {
                Toast.makeText(activity, "Please wait later", Toast.LENGTH_LONG).show()
                return
            }

            val uriModel = UriModel.match(context, imageUri!!)
            if (uriModel == null) {
                Toast.makeText(activity, "Unknown format uri: " + imageUri, Toast.LENGTH_LONG).show()
                return
            }

            if (uriModel is FileUriModel) {
                Toast.makeText(activity, "This image is the local no need to save", Toast.LENGTH_LONG).show()
                return
            }

            val dataSource: DataSource
            try {
                dataSource = uriModel.getDataSource(context, imageUri, null)
            } catch (e: GetDataSourceException) {
                e.printStackTrace()
                Toast.makeText(activity, "The Image is not ready yet", Toast.LENGTH_LONG).show()
                return
            }

            SaveImageAsyncTask(activity, dataSource, imageUri).execute("")
        }

        fun parseFileType(fileName: String): String? {
            val lastIndexOf = fileName.lastIndexOf(".")
            if (lastIndexOf < 0) {
                return null
            }
            val fileType = fileName.substring(lastIndexOf + 1)
            if ("" == fileType.trim { it <= ' ' }) {
                return null
            }
            return fileType
        }

        private inner class MenuItem(val title: String, var clickListener: DialogInterface.OnClickListener?)
    }

    companion object {
        val PARAM_REQUIRED_STRING_IMAGE_URI = "PARAM_REQUIRED_STRING_IMAGE_URI"
        val PARAM_REQUIRED_STRING_LOADING_IMAGE_OPTIONS_KEY = "PARAM_REQUIRED_STRING_LOADING_IMAGE_OPTIONS_KEY"
        val PARAM_REQUIRED_BOOLEAN_SHOW_TOOLS = "PARAM_REQUIRED_BOOLEAN_SHOW_TOOLS"

        fun build(image: Image, loadingImageOptionsId: String?, showTools: Boolean): ImageFragment {
            val bundle = Bundle()
            bundle.putParcelable(PARAM_REQUIRED_STRING_IMAGE_URI, image)
            bundle.putString(PARAM_REQUIRED_STRING_LOADING_IMAGE_OPTIONS_KEY, loadingImageOptionsId)
            bundle.putBoolean(PARAM_REQUIRED_BOOLEAN_SHOW_TOOLS, showTools)
            val fragment = ImageFragment()
            fragment.arguments = bundle
            return fragment
        }
    }
}