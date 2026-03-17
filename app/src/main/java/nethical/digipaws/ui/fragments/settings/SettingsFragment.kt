package nethical.digipaws.ui.fragments.settings

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nethical.digipaws.Constants
import nethical.digipaws.CrashLogger
import nethical.digipaws.R
import nethical.digipaws.databinding.FragmentSettingsBinding
import nethical.digipaws.receivers.AdminReceiver
import nethical.digipaws.services.GeneralFeaturesService
import nethical.digipaws.services.UsageTrackingService
import nethical.digipaws.ui.activity.FragmentActivity
import nethical.digipaws.ui.activity.ReelsMetricsActivity
import nethical.digipaws.ui.activity.SelectAppsActivity
import nethical.digipaws.ui.dialogs.TweakUsageTracker
import nethical.digipaws.ui.fragments.anti_uninstall.ChooseModeFragment
import nethical.digipaws.utils.SavedPreferencesLoader
import nethical.digipaws.utils.ZipUtils

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var savedPreferencesLoader: SavedPreferencesLoader

    private lateinit var selectOverlayAppsLauncher: ActivityResultLauncher<Intent>
    private lateinit var directoryPicker: ActivityResultLauncher<Intent>

    private var isDeviceAdminOn = false
    private var isUsageTrackerOn = false
    private var isGeneralSettingsOn = false
    private var isDisplayOverOtherAppsOn = false
    private var isAntiUninstallOn = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedPreferencesLoader = SavedPreferencesLoader(requireContext())

        setupActivityLaunchers()
        setupClickListeners()
    }

    private fun setupActivityLaunchers() {
        selectOverlayAppsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
                selectedApps?.let {
                    savedPreferencesLoader.setOverlayApps(it.toSet())
                }
            }
        }

        directoryPicker = ZipUtils.registerDirectoryPicker(requireActivity() as AppCompatActivity) { directoryUri ->
            val filename = ZipUtils.createZipFileName()
            val zipUri = createFileInDirectory(directoryUri, filename)
            zipUri?.let {
                ZipUtils.zipSharedPreferencesToUri(requireContext(), it)
            }
        }
    }

    private fun setupClickListeners() {
        // Usage Tracker
        binding.usageTrackerCard.setOnClickListener {
            if (!isDisplayOverOtherAppsOn) {
                showDrawOverOtherAppsDialog()
            } else if (!isGeneralSettingsOn) {
                showAccessibilityDialog("Usage Tracker", UsageTrackingService::class.java)
            }
        }

        binding.usageTrackerChip.setOnClickListener {
            if (!isDisplayOverOtherAppsOn) {
                showDrawOverOtherAppsDialog()
            } else if (!isGeneralSettingsOn) {
                showAccessibilityDialog("Usage Tracker", UsageTrackingService::class.java)
            }
        }

        binding.selectReelUsageStats.setOnClickListener {
            val intent = Intent(requireContext(), ReelsMetricsActivity::class.java)
            startActivity(intent)
        }

        binding.selectAppUsageStats.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java)
            intent.putExtra("fragment", "all_app_usage")
            startActivity(intent)
        }

        binding.btnSelectAppsToShowOverlay.setOnClickListener {
            val intent = Intent(requireContext(), SelectAppsActivity::class.java)
            intent.putStringArrayListExtra(
                "PRE_SELECTED_APPS",
                ArrayList(savedPreferencesLoader.getOverlayApps())
            )
            selectOverlayAppsLauncher.launch(intent)
        }

        binding.btnConfigTracker.setOnClickListener {
            TweakUsageTracker(savedPreferencesLoader).show(
                childFragmentManager,
                "tweak_usage_tracker"
            )
        }

        // Anti Uninstall
        binding.antiUninstallCard.setOnClickListener {
            if (!isDeviceAdminOn) {
                showDeviceAdminDialog()
            } else if (!isGeneralSettingsOn) {
                showAccessibilityDialog("General Features", GeneralFeaturesService::class.java)
            } else if (!isAntiUninstallOn) {
                openAntiUninstallSetup()
            }
        }

        binding.antiUninstallChip.setOnClickListener {
            if (!isDeviceAdminOn) {
                showDeviceAdminDialog()
            } else if (!isGeneralSettingsOn) {
                showAccessibilityDialog("General Features", GeneralFeaturesService::class.java)
            } else if (!isAntiUninstallOn) {
                openAntiUninstallSetup()
            }
        }

        binding.btnUnlockAntiUninstall.setOnClickListener {
            // Show remove anti-uninstall dialog (simplified)
            val mode = requireContext().getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)
                .getInt("mode", -1)

            when (mode) {
                nethical.digipaws.Constants.ANTI_UNINSTALL_TIMED_MODE -> {
                    Toast.makeText(requireContext(), "Timed mode - check remaining days", Toast.LENGTH_SHORT).show()
                }
                nethical.digipaws.Constants.ANTI_UNINSTALL_PASSWORD_MODE -> {
                    // Show password dialog
                    Toast.makeText(requireContext(), "Password mode", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    openAntiUninstallSetup()
                }
            }
        }

        // Tools
        binding.btnBackup.setOnClickListener {
            ZipUtils.showDirectoryPicker(directoryPicker)
        }

        binding.btnShareErrors.setOnClickListener {
            shareCrashLog()
        }

        // Support
        binding.btnDonate.setOnClickListener {
            openUrl("https://digipaws.life/donate")
        }

        binding.btnDiscord.setOnClickListener {
            openUrl("https://discord.com/invite/Vs9mwUtuCN")
        }

        binding.btnGithub.setOnClickListener {
            openUrl("https://github.com/nethical6/digipaws")
        }

        binding.btnCredits.setOnClickListener {
            openUrl("https://digipaws.life/credits")
        }
    }

    private fun showDrawOverOtherAppsDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.enable_2, "Display Over Other Apps"))
            .setMessage(getString(R.string.device_perm_draw_over_other_apps))
            .setPositiveButton("Enable") { _, _ ->
                Toast.makeText(requireContext(), getString(R.string.find_digipaws_and_press_enable), Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${requireContext().packageName}")
                )
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeviceAdminDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.enable_2, "Device Admin"))
            .setMessage(getString(R.string.device_admin_perm))
            .setPositiveButton("Enable") { _, _ ->
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                val componentName = ComponentName(requireContext(), AdminReceiver::class.java)
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enable admin to enable anti uninstall.")
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAccessibilityDialog(title: String, cls: Class<*>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.enable_2, title))
            .setMessage(getString(R.string.accessibility_permission_usage_tracker))
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

    private fun openAntiUninstallSetup() {
        val intent = Intent(requireContext(), FragmentActivity::class.java)
        intent.putExtra("fragment", ChooseModeFragment.FRAGMENT_ID)
        startActivity(intent)
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    private fun shareCrashLog() {
        try {
            val crashLog = CrashLogger.getCrashLog(requireContext())
            if (crashLog.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "DigiPaws Error Log")
                    putExtra(Intent.EXTRA_TEXT, crashLog)
                }
                startActivity(Intent.createChooser(intent, "Share Error Log"))
            } else {
                Toast.makeText(requireContext(), "No crash logs found", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error sharing log", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createFileInDirectory(directoryUri: Uri, filename: String): Uri? {
        return try {
            val docTree = androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), directoryUri)
            docTree?.createFile("application/zip", filename)?.uri
        } catch (e: Exception) {
            null
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private fun checkPermissions() {
        isDisplayOverOtherAppsOn = Settings.canDrawOverlays(requireContext())

        lifecycleScope.launch {
            isUsageTrackerOn = withContext(Dispatchers.IO) {
                isAccessibilityServiceEnabled(UsageTrackingService::class.java)
            }
            isGeneralSettingsOn = withContext(Dispatchers.IO) {
                isAccessibilityServiceEnabled(GeneralFeaturesService::class.java)
            }

            val devicePolicyManager = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(requireContext(), AdminReceiver::class.java)
            isDeviceAdminOn = devicePolicyManager.isAdminActive(componentName)

            val antiUninstallInfo = requireContext().getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)
            isAntiUninstallOn = antiUninstallInfo.getBoolean("is_anti_uninstall_on", false)

            updateUI()
        }
    }

    private fun updateUI() {
        // Usage Tracker
        if (!isDisplayOverOtherAppsOn) {
            binding.usageTrackerChip.text = getString(R.string.disabled)
            binding.usageTrackerChip.setChipIconResource(R.drawable.baseline_warning_24)
            binding.usageTrackerChip.chipIconTint = ContextCompat.getColorStateList(requireContext(), R.color.error_color)
            binding.usageTrackerDesc.text = getString(R.string.please_provide_display_over_other_apps_permission_to_access_this_feature)
            binding.usageTrackerDesc.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_color))
        } else if (!isUsageTrackerOn) {
            binding.usageTrackerChip.text = getString(R.string.disabled)
            binding.usageTrackerChip.setChipIconResource(R.drawable.baseline_warning_24)
            binding.usageTrackerChip.chipIconTint = ContextCompat.getColorStateList(requireContext(), R.color.error_color)
            binding.usageTrackerDesc.text = getString(R.string.warning_usage_tracker_settings)
            binding.usageTrackerDesc.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_color))
        } else {
            updateChip(true, binding.usageTrackerChip)
            binding.usageTrackerDesc.text = getString(R.string.usage_tracker_desc)
            binding.usageTrackerDesc.setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_onSurfaceVariant))
            binding.selectReelUsageStats.isEnabled = true
            binding.btnSelectAppsToShowOverlay.isEnabled = true
            binding.btnConfigTracker.isEnabled = true
        }

        // Anti Uninstall
        if (!isDeviceAdminOn) {
            updateChip(false, binding.antiUninstallChip)
            binding.antiUninstallDesc.text = getString(R.string.please_enable_device_admin)
            binding.antiUninstallDesc.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_color))
        } else if (!isGeneralSettingsOn) {
            updateChip(false, binding.antiUninstallChip)
            binding.antiUninstallDesc.text = getString(R.string.warning_general_settings)
            binding.antiUninstallDesc.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_color))
        } else {
            updateChip(isAntiUninstallOn, binding.antiUninstallChip)
            binding.antiUninstallChip.text = if (isAntiUninstallOn) getString(R.string.setup_complete) else getString(R.string.enter_setup)
            binding.antiUninstallDesc.text = getString(R.string.anti_uninstall_desc)
            binding.antiUninstallDesc.setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_onSurfaceVariant))
            binding.btnUnlockAntiUninstall.isEnabled = isAntiUninstallOn
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
