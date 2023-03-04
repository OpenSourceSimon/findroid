package dev.jdtech.jellyfin.dialogs

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.jdtech.jellyfin.models.JellyfinItem
import dev.jdtech.jellyfin.player.video.R
import dev.jdtech.jellyfin.viewmodels.PlayerViewModel
import java.lang.IllegalStateException

class VideoVersionDialogFragment(
    private val item: JellyfinItem,
    private val viewModel: PlayerViewModel
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val items = item.sources.map { "${it.name} - ${it.type}" }.toTypedArray()
        return activity?.let { activity ->
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.select_a_version)
                .setItems(items) { _, which ->
                    viewModel.loadPlayerItems(item, which)
                }.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }
}
