package nethical.digipaws.ui.fragments.blockers

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nethical.digipaws.R
import nethical.digipaws.databinding.FragmentBlockersBinding
import nethical.digipaws.services.KeywordBlockerService
import nethical.digipaws.services.ViewBlockerService
import nethical.digipaws.ui.activity.ManageKeywordsActivity
import nethical.digipaws.ui.activity.SelectAppsActivity
import nethical.digipaws.ui.activity.TimedActionActivity
import nethical.digipaws.ui.dialogs.TweakKeywordBlocker
import nethical.digipaws.ui.dialogs.TweakKeywordPack
import nethical.digipaws.ui.dialogs.TweakViewBlockerCheatHours
import nethical.digipaws.ui.dialogs.TweakViewBlockerWarning
import nethical.digipaws.utils.SavedPreferencesLoader

class BlockersFragment : Fragment() {

    private var _binding: FragmentBlockersBinding? = null
    private val binding get() = _binding!!

    private lateinit var savedPreferencesLoader: SavedPreferencesLoader

    private lateinit var selectBlockedKeywordsLauncher: ActivityResultLauncher<Intent>
    private lateinit var addCheatHoursLauncher: ActivityResultLauncher<Intent>

    private var isViewBlockerOn = false
    private var isKeywordBlockerOn = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBlockersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedPreferencesLoader = SavedPreferencesLoader(requireContext())

        setupActivityLaunchers()
        setupClickListeners()
    }

    private fun setupActivityLaunchers() {
        selectBlockedKeywordsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val blockedKeywords = result.data?.getStringArrayListExtra("SELECTED_KEYWORDS")
                blockedKeywords?.let {
                    savedPreferencesLoader.saveBlockedKeywords(it.toSet())
                    sendRefreshRequest(nethical.digipaws.services.KeywordBlockerService.INTENT_ACTION_REFRESH_BLOCKED_KEYWORD_LIST)
                }
            }
        }

        addCheatHoursLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            sendRefreshRequest(ViewBlockerService.INTENT_ACTION_REFRESH_VIEW_BLOCKER)
        }
    }

    private fun setupClickListeners() {
        // View Blocker
        binding.viewBlockerCard.setOnClickListener {
            if (!isViewBlockerOn) {
                showAccessibilityDialog("View Blocker", ViewBlockerService::class.java)
            }
        }

        binding.viewBlockerChip.setOnClickListener {
            if (!isViewBlockerOn) {
                showAccessibilityDialog("View Blocker", ViewBlockerService::class.java)
            }
        }

        binding.btnConfigViewblockerWarning.setOnClickListener {
            TweakViewBlockerWarning(savedPreferencesLoader).show(
                childFragmentManager,
                "tweak_view_blocker_warning"
            )
        }

        binding.btnConfigViewblockerCheatHours.setOnClickListener {
            TweakViewBlockerCheatHours(savedPreferencesLoader).show(
                childFragmentManager,
                "tweak_view_blocker_cheat_hours"
            )
        }

        binding.helpReelBlocker.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.about_view_blocker))
                .setMessage(getString(R.string.this_option_has_the_ability_to_block_youtube_shorts_and_instagram_reels_while_allowing_access_to_other_app_features))
                .setPositiveButton(getString(R.string.ok), null)
                .show()
        }

        // Keyword Blocker
        binding.keywordBlockerCard.setOnClickListener {
            if (!isKeywordBlockerOn) {
                showAccessibilityDialog("Keyword Blocker", KeywordBlockerService::class.java)
            }
        }

        binding.keywordBlockerChip.setOnClickListener {
            if (!isKeywordBlockerOn) {
                showAccessibilityDialog("Keyword Blocker", KeywordBlockerService::class.java)
            }
        }

        binding.selectBlockedKeywords.setOnClickListener {
            val intent = Intent(requireContext(), ManageKeywordsActivity::class.java)
            intent.putStringArrayListExtra(
                "PRE_SAVED_KEYWORDS",
                ArrayList(savedPreferencesLoader.loadBlockedKeywords())
            )
            selectBlockedKeywordsLauncher.launch(intent)
        }

        binding.btnManagePreinstalledKeywords.setOnClickListener {
            TweakKeywordPack().show(childFragmentManager, "tweak_keyword_pack")
        }

        binding.btnManageKeywordBlocker.setOnClickListener {
            TweakKeywordBlocker(savedPreferencesLoader).show(
                childFragmentManager,
                "tweak_keyword_blocker"
            )
        }
    }

    private fun showAccessibilityDialog(title: String, cls: Class<*>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.enable_2, title))
            .setMessage(getString(R.string.accessibility_permission_view_blocker))
            .setPositiveButton("Enable") { _, _ ->
                openAccessibilityServiceScreen(cls)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAccessibilityServiceScreen(cls: Class<*>) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val componentName = ComponentName(requireContext(), cls)
            intent.putExtra(":settings:fragment_args_key", componentName.flattenToString())
            val bundle = Bundle()
            bundle.putString(":settings:fragment_args_key", componentName.flattenToString())
            intent.putExtra(":settings:show_fragment_args", bundle)
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private fun checkPermissions() {
        lifecycleScope.launch {
            isViewBlockerOn = withContext(Dispatchers.IO) {
                isAccessibilityServiceEnabled(ViewBlockerService::class.java)
            }
            isKeywordBlockerOn = withContext(Dispatchers.IO) {
                isAccessibilityServiceEnabled(KeywordBlockerService::class.java)
            }

            updateUI()
        }
    }

    private fun updateUI() {
        // View Blocker
        updateChip(isViewBlockerOn, binding.viewBlockerChip)
        binding.btnConfigViewblockerWarning.isEnabled = isViewBlockerOn
        binding.btnConfigViewblockerCheatHours.isEnabled = isViewBlockerOn

        if (!isViewBlockerOn) {
            binding.viewBlockerDesc.text = getString(R.string.warning_view_blocker_settings)
            binding.viewBlockerDesc.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_color))
        } else {
            binding.viewBlockerDesc.text = getString(R.string.view_blocker_desc)
            binding.viewBlockerDesc.setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_onSurfaceVariant))
        }

        // Keyword Blocker
        updateChip(isKeywordBlockerOn, binding.keywordBlockerChip)
        binding.selectBlockedKeywords.isEnabled = isKeywordBlockerOn
        binding.btnManagePreinstalledKeywords.isEnabled = isKeywordBlockerOn
        binding.btnManageKeywordBlocker.isEnabled = isKeywordBlockerOn

        if (!isKeywordBlockerOn) {
            binding.keywordBlockerDesc.text = getString(R.string.warning_keywords_settings)
            binding.keywordBlockerDesc.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_color))
        } else {
            binding.keywordBlockerDesc.text = getString(R.string.keyword_blocker_desc)
            binding.keywordBlockerDesc.setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_onSurfaceVariant))
        }
    }

    private fun updateChip(isEnabled: Boolean, chip: Chip) {
        if (isEnabled) {
            chip.text = getString(R.string.enabled)
            chip.chipIcon = null
        } else {
            chip.text = getString(R.string.disabled)
            chip.setChipIconResource(R.drawable.baseline_warning_24)
            chip.chipIconTint = ContextCompat.getColorStateList(requireContext(), R.color.error_color)
        }
    }

    private fun isAccessibilityServiceEnabled(serviceClass: Class<out AccessibilityService>): Boolean {
        val serviceName = ComponentName(requireContext(), serviceClass).flattenToString()
        val enabledServices = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val isAccessibilityEnabled = Settings.Secure.getInt(
            requireContext().contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        return isAccessibilityEnabled == 1 && enabledServices.contains(serviceName)
    }

    private fun sendRefreshRequest(action: String) {
        val intent = Intent(action)
        requireContext().sendBroadcast(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
