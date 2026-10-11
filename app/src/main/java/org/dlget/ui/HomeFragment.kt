package org.dlget.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.dlget.R
import org.dlget.adapters.VidInfoAdapter
import org.dlget.adapters.VidInfoListener
import org.dlget.databinding.FragmentHomeBinding
import org.dlget.model.VidInfoItem
import org.dlget.vm.LoadState
import org.dlget.vm.VidInfoViewModel
import org.dlget.work.DownloadWorker
import com.yausername.youtubedl_android.mapper.VideoFormat
import kotlinx.coroutines.launch

class HomeFragment : Fragment(), SearchView.OnQueryTextListener,
    DownloadPathDialogFragment.DialogListener {

    private var _binding: FragmentHomeBinding? = null
    // Subtitle choice carried over when the user changes the download folder
    private var pendingEmbedSubs = false
    // Toolbar search item, so the empty-screen placeholder can open the search box
    private var searchItem: MenuItem? = null
    private val binding get() = _binding!!

    // Download progress notifications need this on Android 13+; the download
    // itself proceeds regardless of the answer.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)
        initViews()
    }

    private fun initViews() {
        binding.startTv.setOnClickListener { openSearch() }
        val vidFormatsVm =
            ViewModelProvider(requireActivity()).get(VidInfoViewModel::class.java)
        binding.recyclerview.adapter =
            VidInfoAdapter(VidInfoListener {
                vidFormatsVm.selectedItem = it
                DownloadPathDialogFragment.newInstance(it is VidInfoItem.VidFormatItem).show(
                    childFragmentManager,
                    downloadLocationDialogTag
                )
            })
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vidFormatsVm.vidFormats.collect { t ->
                        (binding.recyclerview.adapter as VidInfoAdapter).fill(t)
                        // An empty RecyclerView still covers the whole screen and eats the
                        // tap meant for the placeholder underneath it
                        binding.recyclerview.visibility = if (t == null) GONE else VISIBLE
                    }
                }
                launch {
                    vidFormatsVm.loadState.collect { t ->
                        when (t) {
                            is LoadState.Initial -> {
                                binding.loadingPb.visibility = GONE
                                binding.startTv.visibility = VISIBLE
                            }
                            is LoadState.Loading -> {
                                binding.loadingPb.visibility = VISIBLE
                                binding.startTv.visibility = GONE
                            }
                            is LoadState.Loaded -> {
                                binding.loadingPb.visibility = GONE
                                binding.startTv.visibility = GONE
                            }
                            is LoadState.Error -> {
                                binding.loadingPb.visibility = GONE
                                binding.startTv.visibility = VISIBLE
                                Toast.makeText(context, t.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.search).isVisible = true
        (activity as? MainActivity)?.supportActionBar?.themedContext?.let {
            val searchView = SearchView(it)
            menu.findItem(R.id.search).actionView = searchView
            searchView.setOnQueryTextListener(this)
        }
        searchItem = menu.findItem(R.id.search)
        super.onPrepareOptionsMenu(menu)
    }

    /** Expand the toolbar search box and focus it (used by the empty Home screen). */
    private fun openSearch() {
        // Clicking the toolbar's search action item is the same path the user would
        // take; expandActionView() is unreliable here because onPrepareOptionsMenu
        // replaces the action view each time the menu is built.
        val actionButton = activity?.findViewById<View>(R.id.search)
        if (actionButton != null) {
            actionButton.performClick()
        } else {
            searchItem?.let { if (!it.isActionViewExpanded) it.expandActionView() }
        }
        (searchItem?.actionView as? SearchView)?.let {
            it.isIconified = false
            it.requestFocus()
        }
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        query?.let { processSearch(it) }
        return true
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        return true
    }

    private fun processSearch(url: String) {
        val vidFormatsVm =
            ViewModelProvider(requireActivity()).get(VidInfoViewModel::class.java)
        vidFormatsVm.fetchInfo(url)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OPEN_DIRECTORY_REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK) {
                    data?.data?.let {
                        requireActivity().contentResolver?.takePersistableUriPermission(
                            it,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        setDefaultDownloadLocation(it.toString())
                        val vidFormatsVm =
                            ViewModelProvider(requireActivity()).get(VidInfoViewModel::class.java)
                        vidFormatsVm.selectedItem?.let {
                            item -> startDownload(item, it.toString(), pendingEmbedSubs)
                        }
                    }
                }
            }
        }
    }

    // sets default download location if unset
    private fun setDefaultDownloadLocation(path: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        prefs.getString(getString(R.string.download_location_key), null) ?: prefs.edit()
            .putString(getString(R.string.download_location_key), path).apply()
    }

    private fun expectedBytes(fmt: VideoFormat, formats: List<VideoFormat>): Long {
        // A video-only format is merged with bestaudio, so the selected format's own
        // size alone would let the progress bar run past 100%.
        val own = maxOf(fmt.fileSize, fmt.fileSizeApproximate)
        if (own == 0L) return 0L  // size unknown - let the worker fall back to yt-dlp's percent
        if (fmt.vcodec == "none" || fmt.acodec != "none") return own
        val audio = formats.filter { it.vcodec == "none" && it.acodec != "none" }
            .maxOfOrNull { maxOf(it.fileSize, it.fileSizeApproximate) } ?: 0L
        return own + audio
    }

    private fun startDownload(item: VidInfoItem, downloadDir: String, embedSubs: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val vidInfo = when (item) {
            is VidInfoItem.VidFormatItem -> item.vidInfo
            is VidInfoItem.VidAudioMp3Item -> item.vidInfo
            else -> return
        }
        val workTag = vidInfo.id
        val workManager = WorkManager.getInstance(requireActivity().applicationContext)
        val workData = when (item) {
            is VidInfoItem.VidFormatItem -> workDataOf(
                DownloadWorker.urlKey to vidInfo.webpageUrl,
                DownloadWorker.nameKey to vidInfo.title,
                DownloadWorker.formatIdKey to item.vidFormat.formatId,
                DownloadWorker.acodecKey to item.vidFormat.acodec,
                DownloadWorker.vcodecKey to item.vidFormat.vcodec,
                DownloadWorker.downloadDirKey to downloadDir,
                DownloadWorker.sizeKey to item.vidFormat.fileSize,
                DownloadWorker.totalSizeKey to expectedBytes(item.vidFormat, vidInfo.formats.orEmpty()),
                DownloadWorker.taskIdKey to vidInfo.id,
                DownloadWorker.embedSubsKey to embedSubs
            )
            is VidInfoItem.VidAudioMp3Item -> workDataOf(
                DownloadWorker.urlKey to vidInfo.webpageUrl,
                DownloadWorker.nameKey to vidInfo.title,
                DownloadWorker.formatIdKey to "bestaudio",
                DownloadWorker.acodecKey to "none",
                DownloadWorker.vcodecKey to "none",
                DownloadWorker.downloadDirKey to downloadDir,
                DownloadWorker.sizeKey to 0L,
                DownloadWorker.taskIdKey to vidInfo.id,
                DownloadWorker.mp3Key to true
            )
            else -> return
        }
        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag(workTag)
            .setInputData(workData)
            .build()

        // Ask WorkManager off the main thread: ListenableFuture.get() here would block
        // the click handler, and getOrNull(0) can read a stale CANCELLED/SUCCEEDED entry
        // left behind by an earlier REPLACE while a download is actually running.
        val workInfos = workManager.getWorkInfosByTag(workTag)
        workInfos.addListener({
            if (!isAdded) return@addListener
            val infos = try {
                workInfos.get() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            val running = infos.any {
                it.state === WorkInfo.State.RUNNING || it.state === WorkInfo.State.ENQUEUED
            }
            if (running) {
                Toast.makeText(
                    context,
                    R.string.download_already_running,
                    Toast.LENGTH_LONG
                ).show()
                return@addListener
            }
            // REPLACE so a finished/failed download of the same video can be re-downloaded
            workManager.enqueueUniqueWork(
                workTag,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            Toast.makeText(
                context,
                R.string.download_queued,
                Toast.LENGTH_LONG
            ).show()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    override fun onOk(dialog: DownloadPathDialogFragment) {
        val vidFormatsVm =
            ViewModelProvider(requireActivity()).get(VidInfoViewModel::class.java)
        val path = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getString(getString(R.string.download_location_key), null)
        if (path == null) {
            Toast.makeText(context, R.string.invalid_download_location, Toast.LENGTH_SHORT).show()
            return
        }
        vidFormatsVm.selectedItem?.let { startDownload(it, path, dialog.embedSubs) }
    }

    override fun onFilePicker(dialog: DownloadPathDialogFragment) {
        // The dialog is dismissed when the picker opens, so keep the subtitle choice
        // for the download that is started from onActivityResult.
        pendingEmbedSubs = dialog.embedSubs
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }
        startActivityForResult(intent, OPEN_DIRECTORY_REQUEST_CODE)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val downloadLocationDialogTag = "download_location_chooser_dialog"
        private const val OPEN_DIRECTORY_REQUEST_CODE = 42069
    }

}