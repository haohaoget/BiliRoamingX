package app.revanced.bilibili.settings.fragments

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.revanced.bilibili.account.Accounts
import app.revanced.bilibili.model.DownloadEntry
import app.revanced.bilibili.model.saveFilename
import app.revanced.bilibili.model.showName
import app.revanced.bilibili.patches.main.ApplicationDelegate
import app.revanced.bilibili.settings.search.annotation.SettingFragment
import app.revanced.bilibili.utils.*
import java.io.File

@SettingFragment("biliroaming_setting_tool")
class ToolFragment : BiliRoamingBaseSettingFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        findPreference<Preference>("route")?.onClick { route();true }
        findPreference<Preference>("export_access_key")?.onClick {
            setClipboardContent(content = Accounts.accessKey)
            Toasts.showShortWithId("biliroaming_access_key_copied")
            true
        }
        findPreference<Preference>("export_cache_video")?.onClick {
            showExportSelectDialog()
            true
        }
    }

    private fun route() {
        val editText = EditText(context)
        editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val hintUrl = Utils.getString("biliroaming_route_hint")
        editText.hint = hintUrl
        AlertDialog.Builder(context)
            .setView(editText)
            .setTitle(Utils.getString("biliroaming_route_title"))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Go") { _, _ ->
                val editUrl = editText.text.toString().trim()
                val uri = Uri.parse(editUrl.ifEmpty { hintUrl })
                runCatching {
                    Utils.routeTo(uri, context)
                    if (editUrl.isEmpty()) Utils.runOnMainThread(300) {
                        Toasts.showShortWithId("biliroaming_you_were_cheated")
                    }
                }.onFailure {
                    Logger.error(it) { "route failed, uri: $uri" }
                    Toasts.showShortWithId("biliroaming_open_failed")
                }
            }.create().onShow {
                getButton(DialogInterface.BUTTON_POSITIVE).isAllCaps = false
            }.constraintSize(maxHeight = -1).show()
    }

    private fun showExportSelectDialog() = Utils.async {
        val downloadDir = File(Utils.getContext().getExternalFilesDir(null)!!.parentFile, "download")
        val entries = downloadDir.listFiles().orEmpty().flatMap {
            it.listFiles().orEmpty().asIterable()
        }.mapNotNull { dir ->
            val entry = File(dir, "entry.json").runCatchingOrNull {
                inputStream().use { it.fromJson<DownloadEntry>() }
            } ?: return@mapNotNull null
            if (!entry.isCompleted)
                return@mapNotNull null
            val videoQuality = entry.videoQuality
            val saveDir = File(dir, videoQuality.toString())
            if (!saveDir.isDirectory)
                return@mapNotNull null
            DownloadEntrySelection(entry, saveDir)
        }.toList()
        Utils.runOnMainThread {
            val context = hostContext
            val adapter = DownloadEntryAdapter(context, entries)
            val recyclerView = RecyclerView(context).apply {
                this.adapter = adapter
                layoutManager = LinearLayoutManager(context)
                (itemAnimator as? DefaultItemAnimator)?.supportsChangeAnimations = false
                clipToPadding = true
                updatePadding(top = 4.dp)
            }
            AlertDialog.Builder(context)
                .setTitle(Utils.getString("biliroaming_export_cache_video_title"))
                .setView(recyclerView)
                .setPositiveButton(Utils.getString("biliroaming_export")) { _, _ ->
                    val selections = entries.filter { it.selected }
                    if (selections.isNotEmpty())
                        exportVideos(selections)
                }
                .setNegativeButton(Utils.getString("biliroaming_select_or_cancel_all"), null)
                .setNeutralButton(android.R.string.cancel, null)
                .create().constraintSize(-1)
                .onShow {
                    getButton(AlertDialog.BUTTON_NEGATIVE).onClick {
                        adapter.selectOrCancelAll()
                    }
                }
                .show()
        }
    }

    companion object {
        @JvmStatic
        private fun exportVideos(selections: List<DownloadEntrySelection>) {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val videosDir = File(downloadsDir, "bilibili/videos")
            fun save() = runCatching {
                Toasts.showShortWithId("biliroaming_exporting")
                videosDir.mkdirs()
                for (selection in selections) {
                    val entry = selection.entry
                    val saveDir = selection.saveDir
                    val videoPath = File(saveDir, "video.m4s").absolutePath
                    val audioPath = File(saveDir, "audio.m4s").absolutePath
                    val exportPath = File(videosDir, "${entry.saveFilename}.mp4").absolutePath
                    MediaMerger.merge(videoPath, audioPath, exportPath)
                }
            }.onSuccess {
                Toasts.showLongWithId("biliroaming_export_success", videosDir.absolutePath)
                MediaScannerConnection.scanFile(Utils.getContext(), videosDir.list().orEmpty(), null, null)
            }.onFailure {
                Logger.error(it) { "cache video export failed" }
                Toasts.showShortWithId("biliroaming_export_failed")
            }
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
                Utils.async { save() }
            } else {
                val activity = ApplicationDelegate.requireTopActivity()
                activity.requestPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) { granted, shouldExplain ->
                    if (granted) {
                        Utils.async { save() }
                    } else if (shouldExplain) {
                        Toasts.showShortWithId("biliroaming_write_storage_failed")
                    }
                }
            }
        }
    }
}

class DownloadEntrySelection(val entry: DownloadEntry, val saveDir: File, var selected: Boolean = false)

class DownloadEntryAdapter(
    private val context: Context,
    private val entries: List<DownloadEntrySelection>
) : RecyclerView.Adapter<DownloadEntryViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadEntryViewHolder {
        val cell = DownloadEntryCell(context).apply {
            setRippleBackground()
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
        }
        val viewHolder = DownloadEntryViewHolder(cell)
        cell.checkBox.isClickable = false
        cell.onClick {
            val pos = viewHolder.bindingAdapterPosition
            val selection = entries[pos]
            selection.selected = !selection.selected
            notifyItemChanged(pos)
        }
        return viewHolder
    }

    override fun getItemCount() = entries.size

    override fun onBindViewHolder(holder: DownloadEntryViewHolder, position: Int) {
        val selection = entries[position]
        holder.cell.name.text = selection.entry.showName
        holder.cell.checkBox.isChecked = selection.selected
    }

    fun selectOrCancelAll() {
        if (entries.all { it.selected }) {
            entries.forEach { it.selected = false }
            notifyItemRangeChanged(0, entries.size)
        } else {
            entries.forEachIndexed { index, entry ->
                if (!entry.selected) {
                    entry.selected = true
                    notifyItemChanged(index)
                }
            }
        }
    }
}

class DownloadEntryViewHolder(val cell: DownloadEntryCell) : RecyclerView.ViewHolder(cell)

@SuppressLint("ViewConstructor")
class DownloadEntryCell(context: Context) : LinearLayout(context) {
    val name: TextView = Widgets.createTintView<TextView>(context, "TextView").apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT).apply {
            weight = 1F
        }
    }
    val checkBox: CheckBox = Widgets.createTintView<CheckBox>(context, "CheckBox").apply {
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        background = null
    }

    init {
        addView(name)
        addView(checkBox)
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(16.dp, 8.dp, 16.dp, 8.dp)
    }
}
