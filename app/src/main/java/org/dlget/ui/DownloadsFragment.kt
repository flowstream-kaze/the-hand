package org.dlget.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.launch
import org.dlget.R
import org.dlget.adapters.DownloadsAdapter
import org.dlget.database.Download
import org.dlget.databinding.FragmentDownloadsListBinding
import org.dlget.vm.DownloadsViewModel

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsListBinding? = null
    private val binding get() = _binding!!
    private lateinit var downloadsViewModel: DownloadsViewModel
    private lateinit var adapter: DownloadsAdapter
    private var selectionActionMode: ActionMode? = null
    // Last list seen, so clearing can cancel the work behind any active row
    private var lastDownloads: List<Download> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)
        adapter = DownloadsAdapter(
            onSelectionRequested = { startSelectionMode() },
            onSelectionChanged = { count -> updateSelectionMode(count) }
        )
        binding.list.adapter = adapter
        downloadsViewModel = ViewModelProvider(this).get(DownloadsViewModel::class.java)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadsViewModel.allDownloads.collect { downloads ->
                    lastDownloads = downloads
                    adapter.addItems(downloads)
                    activity?.invalidateOptionsMenu()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_downloads, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        val hasDownloads = lastDownloads.isNotEmpty()
        menu.findItem(R.id.select_entries)?.isVisible = hasDownloads
        menu.findItem(R.id.clear_history)?.isVisible = hasDownloads
        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.select_entries -> {
            startSelectionMode()
            true
        }
        R.id.clear_history -> {
            confirmClearHistory()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun startSelectionMode() {
        if (selectionActionMode != null) return
        selectionActionMode = (requireActivity() as AppCompatActivity)
            .startSupportActionMode(selectionCallback)
        updateSelectionMode(adapter.selectedIds().size)
    }

    private fun updateSelectionMode(count: Int) {
        val mode = selectionActionMode ?: return
        mode.title = if (count == 0) {
            getString(R.string.select_entries)
        } else {
            resources.getQuantityString(R.plurals.entries_selected, count, count)
        }
        mode.menu.findItem(R.id.remove_selected)?.isEnabled = count > 0
    }

    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_download_selection, menu)
            adapter.setSelectionMode(true)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != R.id.remove_selected) return false
            confirmRemoveSelected()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            adapter.setSelectionMode(false)
            selectionActionMode = null
        }
    }

    private fun confirmRemoveSelected() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        val count = ids.size
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(resources.getQuantityString(R.plurals.remove_entries_title, count, count))
            .setMessage(R.string.remove_entries_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.remove_from_list) { _, _ ->
                downloadsViewModel.deleteByIds(ids)
                selectionActionMode?.finish()
                Toast.makeText(
                    requireContext(),
                    resources.getQuantityString(R.plurals.entries_removed, count, count),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clear_history_title)
            .setMessage(R.string.clear_history_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_clear) { _, _ -> clearHistory() }
            .show()
    }

    private fun clearHistory() {
        // Stop in-flight downloads first: deleting a row while its download runs would
        // leave the finished file on disk with nothing pointing at it.
        val workManager = WorkManager.getInstance(requireContext().applicationContext)
        lastDownloads
            .filter { it.status == Download.STATUS_ACTIVE && it.taskId.isNotEmpty() }
            .forEach { row ->
                workManager.cancelAllWorkByTag(row.taskId)
                try {
                    YoutubeDL.getInstance().destroyProcessById(row.taskId)
                } catch (e: Exception) {
                    // Not initialised, or the process already exited - the cancel above stands
                }
            }
        downloadsViewModel.clearAll()
        Toast.makeText(requireContext(), R.string.history_cleared, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        selectionActionMode?.finish()
        super.onDestroyView()
        _binding = null
    }
}
