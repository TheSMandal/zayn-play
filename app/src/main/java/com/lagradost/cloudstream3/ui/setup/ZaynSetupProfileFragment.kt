package com.lagradost.cloudstream3.ui.setup

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentZaynSetupProfileBinding
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.txt

/**
 * Zayn Play setup: Profile (step 1 of 6). Fresh build, v1.0.10.
 * Replaces SetupFragmentProfile. Behaviour is identical to the old step
 * (name field, 8 presets or own-photo upload, skippable, saved name+photo
 * show in the home header avatar and the Settings profile card) — only the
 * design matches the preview: setup-top header, "Create your profile",
 * letter-tile avatars (preview .avatar-grid), red-gradient Next.
 *
 * The stored value stays the preset KEY (or the uploaded-photo file path),
 * so the home header and Settings card keep showing it via
 * ZaynProfile.loadInto / presetDrawableRes — unchanged behaviour.
 */
class ZaynSetupProfileFragment : BaseFragment() {
    private var _binding: FragmentZaynSetupProfileBinding? = null
    private val binding get() = _binding!!

    private data class AvatarTileViews(val key: String, val cell: View, val ring: View, val tile: TextView)

    /** Preset key -> (letter, gradient colours) in preview grid order.
     *  Letters and gradients are the preview's; the gradient colours are
     *  matched to the preset drawables (Z red, R blue, A purple, S green
     *  via the Zayn tile, M amber, N cyan, K pink, T slate). */
    private val avatarTiles = listOf(
        Triple("preset_red", "Z", intArrayOf(0xFFFF5147.toInt(), 0xFFB3000C.toInt())),
        Triple("preset_blue", "R", intArrayOf(0xFF3B82F6.toInt(), 0xFF1E3A8A.toInt())),
        Triple("preset_purple", "A", intArrayOf(0xFFA855F7.toInt(), 0xFF5B21B6.toInt())),
        Triple("preset_zayn", "S", intArrayOf(0xFF22C55E.toInt(), 0xFF14532D.toInt())),
        Triple("preset_orange", "M", intArrayOf(0xFFF59E0B.toInt(), 0xFFB45309.toInt())),
        Triple("preset_teal", "N", intArrayOf(0xFF06B6D4.toInt(), 0xFF155E75.toInt())),
        Triple("preset_pink", "K", intArrayOf(0xFFEC4899.toInt(), 0xFF831843.toInt())),
        Triple("preset_dark_blue", "T", intArrayOf(0xFF64748B.toInt(), 0xFF0F172A.toInt())),
    )

    private val tileViews = mutableListOf<AvatarTileViews>()

    private var selectedPreset = "preset_red"
    private var hasUploadedPhoto = false

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val ctx = context ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        ioSafe {
            val bytes = try {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (_: Exception) {
                null
            }
            val savedPath = bytes?.let { ZaynProfile.saveUploadedPhoto(ctx, it) }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                if (savedPath != null) {
                    hasUploadedPhoto = true
                    updateTileSelection()
                    updatePreviewPhoto()
                    txt(ctx, R.string.zayn_setup_profile_photo_saved)
                } else {
                    txt(ctx, R.string.zayn_setup_profile_photo_failed)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentZaynSetupProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tileViews.clear()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        safe {
            val ctx = context ?: return@safe

            val avatar = ZaynProfile.getAvatar(ctx)
            hasUploadedPhoto = ZaynProfile.isFileAvatar(avatar)
            // Fresh installs default to the preview's Z/red tile; a stored
            // preset (or an uploaded photo) is kept exactly as saved.
            selectedPreset = if (hasUploadedPhoto) {
                "preset_red"
            } else {
                avatar?.takeIf { it.startsWith(ZaynProfile.PRESET_PREFIX) } ?: "preset_red"
            }

            binding.apply {
                profileNameInput.setText(ZaynProfile.getName(ctx) ?: "")
                profileNameInput.doAfterTextChanged { text ->
                    val c = context ?: return@doAfterTextChanged
                    ZaynProfile.saveName(c, text?.toString() ?: "")
                    updatePreviewLetter()
                    updatePreviewName()
                }

                tileViews.addAll(
                    listOf(
                        AvatarTileViews("preset_red", profileAvatarZ, profileAvatarZRing, profileAvatarZTile),
                        AvatarTileViews("preset_blue", profileAvatarR, profileAvatarRRing, profileAvatarRTile),
                        AvatarTileViews("preset_purple", profileAvatarA, profileAvatarARing, profileAvatarATile),
                        AvatarTileViews("preset_zayn", profileAvatarS, profileAvatarSRing, profileAvatarSTile),
                        AvatarTileViews("preset_orange", profileAvatarM, profileAvatarMRing, profileAvatarMTile),
                        AvatarTileViews("preset_teal", profileAvatarN, profileAvatarNRing, profileAvatarNTile),
                        AvatarTileViews("preset_pink", profileAvatarK, profileAvatarKRing, profileAvatarKTile),
                        AvatarTileViews("preset_dark_blue", profileAvatarT, profileAvatarTRing, profileAvatarTTile),
                    )
                )
                tileViews.forEach { views ->
                    val colors = avatarTiles.firstOrNull { it.first == views.key }?.third ?: return@forEach
                    views.tile.background = tileBackground(colors, isTileSelected(views.key))
                    views.cell.setOnClickListener { selectPreset(views.key) }
                }

                profileUploadButton.setOnClickListener {
                    pickPhoto.launch("image/*")
                }

                setupNext.setOnClickListener {
                    val c = context ?: return@setOnClickListener
                    ZaynProfile.saveName(c, profileNameInput.text?.toString() ?: "")
                    if (!hasUploadedPhoto) ZaynProfile.saveAvatar(c, selectedPreset)
                    findNavController().navigate(R.id.navigation_setup_language)
                }

                setupSkip.setOnClickListener {
                    setKey(HAS_DONE_SETUP_KEY, true)
                    findNavController().navigate(R.id.navigation_home)
                }

                updateTileSelection()
                updatePreviewPhoto()
                updatePreviewName()
            }
        }
    }

    private fun selectPreset(key: String) {
        selectedPreset = key
        hasUploadedPhoto = false
        val ctx = context ?: return
        ZaynProfile.saveAvatar(ctx, key)
        updateTileSelection()
        updatePreviewPhoto()
    }

    private fun isTileSelected(key: String): Boolean =
        key == selectedPreset && !hasUploadedPhoto

    private fun updateTileSelection() {
        tileViews.forEach { views ->
            val colors = avatarTiles.firstOrNull { it.first == views.key }?.third ?: return@forEach
            val selected = isTileSelected(views.key)
            views.ring.visibility = if (selected) View.VISIBLE else View.GONE
            views.tile.background = tileBackground(colors, selected)
        }
    }

    /** Preview circle: uploaded photo when one is set, else the letter tile. */
    private fun updatePreviewPhoto() {
        val b = _binding ?: return
        val ctx = context ?: return
        if (hasUploadedPhoto) {
            b.profilePreviewLetter.visibility = View.GONE
            b.profilePreviewPhoto.visibility = View.VISIBLE
            ZaynProfile.loadInto(b.profilePreviewPhoto, ctx)
        } else {
            b.profilePreviewPhoto.visibility = View.GONE
            b.profilePreviewLetter.visibility = View.VISIBLE
            updatePreviewLetter()
        }
    }

    /** Preview letter: first letter of the typed name, else the tile letter. */
    private fun updatePreviewLetter() {
        val b = _binding ?: return
        if (hasUploadedPhoto) return
        val tile = avatarTiles.firstOrNull { it.first == selectedPreset } ?: avatarTiles[0]
        b.profilePreviewLetter.apply {
            text = previewLetter()
            background = letterGradient(tile.third)
        }
    }

    private fun previewLetter(): String {
        val name = _binding?.profileNameInput?.text?.toString()?.trim().orEmpty()
        if (name.isNotEmpty()) return name.first().uppercase()
        return avatarTiles.firstOrNull { it.first == selectedPreset }?.second ?: "Z"
    }

    private fun updatePreviewName() {
        val b = _binding ?: return
        val name = b.profileNameInput.text?.toString()?.trim().orEmpty()
        b.profilePreviewName.text =
            name.ifEmpty { getString(R.string.zayn_setup_profile_your_name) }
    }

    private fun tileBackground(colors: IntArray, selected: Boolean): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2.5f), if (selected) Color.WHITE else Color.TRANSPARENT)
        }

    private fun letterGradient(colors: IntArray): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2.5f), Color.argb(64, 255, 255, 255))
        }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()
}
