package org.dlget.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.dlget.R
import org.dlget.databinding.FragmentYoutubeDlBinding
import org.dlget.work.CommandWorker

class YoutubeDlFragment : Fragment(), View.OnClickListener {

    private var _binding: FragmentYoutubeDlBinding? = null
    private val binding get() = _binding!!

    private var command: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentYoutubeDlBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.commandBtn.setOnClickListener(this)
    }

    override fun onClick(view: View) {
        when (view?.id) {
            R.id.command_btn -> {
                command = binding.commandEt.text.toString()
                if (!command.isNullOrBlank()) {
                    startCommand(command!!)
                }
            }
        }
    }

    private fun startCommand(command: String) {
        val workTag = CommandWorker.commandWorkTag
        val workManager = WorkManager.getInstance(requireActivity().applicationContext)
        val state =
            workManager.getWorkInfosByTag(workTag).get()?.getOrNull(0)?.state
        val running = state === WorkInfo.State.RUNNING || state === WorkInfo.State.ENQUEUED
        if (running) {
            Toast.makeText(
                context,
                R.string.command_already_running,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val workData = workDataOf(
            CommandWorker.commandKey to command
        )
        val workRequest = OneTimeWorkRequestBuilder<CommandWorker>()
            .addTag(workTag)
            .setInputData(workData)
            .build()

        workManager.enqueueUniqueWork(
            workTag,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        Toast.makeText(
            context,
            R.string.command_queued,
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}