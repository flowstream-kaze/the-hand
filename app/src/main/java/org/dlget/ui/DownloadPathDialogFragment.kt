package org.dlget.ui

import android.app.Dialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.preference.PreferenceManager
import com.google.android.material.checkbox.MaterialCheckBox
import org.dlget.R
import org.dlget.databinding.DialogFragmentDownloadPathBinding

class DownloadPathDialogFragment : DialogFragment() {

    private lateinit var listener: DialogListener
    private var embedSubsCb: MaterialCheckBox? = null

    /** Whether the user asked for embedded subtitles (false when the option is hidden). */
    val embedSubs: Boolean
        get() = embedSubsCb?.takeIf { it.visibility == View.VISIBLE }?.isChecked ?: false

    interface DialogListener {
        fun onOk(dialog: DownloadPathDialogFragment)
        fun onFilePicker(dialog: DownloadPathDialogFragment)
    }

    companion object {
        private const val ARG_SHOW_SUBS = "show_subs_option"

        fun newInstance(showSubsOption: Boolean) = DownloadPathDialogFragment().apply {
            arguments = bundleOf(ARG_SHOW_SUBS to showSubsOption)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val builder = AlertDialog.Builder(it)
            val binding = DialogFragmentDownloadPathBinding.inflate(layoutInflater)
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val location = sharedPrefs.getString(getString(R.string.download_location_key), null)
            if (location != null) {
                val docId = try {
                    DocumentsContract.getTreeDocumentId(Uri.parse(location))
                } catch (e: Exception) {
                    // A corrupt/legacy preference value must not crash the dialog
                    null
                }
                docId?.apply { binding.downloadPathTv.text = docId }
                    ?: run { binding.downloadPathTv.text = location }
            } else {
                binding.downloadPathTv.setText(R.string.val_not_set)
            }
            embedSubsCb = binding.embedSubsCb
            binding.embedSubsCb.visibility =
                if (arguments?.getBoolean(ARG_SHOW_SUBS, true) == true) View.VISIBLE else View.GONE

            builder.setView(binding.root)
                .setIcon(R.drawable.ic_folder_24dp)
                .setTitle(R.string.download_location_title)
                .setNegativeButton(R.string.action_choose_folder)
                { _, _ ->
                    listener.onFilePicker(this)
                }
                .setPositiveButton(android.R.string.ok)
                { _, _ ->
                    listener.onOk(this)
                }

            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        try {
            listener = parentFragment as DialogListener
        } catch (e: ClassCastException) {
            // The activity doesn't implement the interface, throw exception
            throw ClassCastException(
                (context.toString() +
                        " must implement DialogListener")
            )
        }
    }

}