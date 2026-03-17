package nethical.digipaws.ui.fragments.focus

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nethical.digipaws.R
import nethical.digipaws.databinding.FragmentFocusBinding
import nethical.digipaws.services.AppBlockerService
import nethical.digipaws.services.GeneralFeaturesService
import nethical.digipaws.ui.activity.SelectAppsActivity
import nethical.digipaws.ui.activity.TimedActionActivity
import nethical.digipaws.ui.dialogs.StartFocusMode
import nethical.digipaws.ui.dialogs.TweakGrayScaleMode
import nethical.digipaws.utils.SavedPreferencesLoader
import rikka.shizuku.Shizuku

class FocusFragment : Fragment() {

    private var _binding: FragmentFocusBinding? = null
    private val binding get() = _binding!!

    private lateinit var savedPreferencesLoader: SavedPreferencesLoader

    private lateinit var selectBlockedAppsLauncher: ActivityResultLauncher<Intent>
    private lateinit var selectFocusAppsLauncher: ActivityResultLauncher<Intent>
    private lateinit var selectGrayscaleAppsLauncher: ActivityResultLauncher<Intent>
    private lateinit var addAutoFocusHoursLauncher: ActivityResultLauncher<Intent>

    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    private var isAppBlockerOn = false
    private var isGeneralSettingsOn = false
    private var isShizukuOn = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFocusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedPreferencesLoader = SavedPreferencesLoader(requireContext())

        setupActivityLaunchers()
        setupClickListeners()

        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) {
                isShizukuOn = true
                updateShizukuUI()
            }
        }
    }

    private fun setupActivityLaunchers() {
        selectBlockedAppsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
                selectedApps?.let {
                    savedPreferencesLoader.saveBlockedApps(it.toString())
                    sendRefreshRequest(AppBlockerService.INTENT_ACTION_REFRESH_APP_BLOCKER)
                }
            }
        }

        selectFocusAppsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
                selectedApps?.let {
                    savedPreferencesLoader.saveFocusModeSelectedApps(it)
                }
            }
        }

        selectGrayscaleAppsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
                selectedApps?.let {
                    savedPreferencesLoader.saveGrayScaleApps(it.toSet())
                    sendRefreshRequest(GeneralFeaturesService.INTENT_ACTION_REFRESH_GRAYSCALE)
                }
            }
        }

        addAutoFocusHoursLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            sendRefreshRequest(AppBlockerService.INTENT_ACTION_REFRESH_FOCUS_MODE)
        }

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                showStartFocusModeDialog()
            } else {
                Toast.makeText(requireContext(), "Notification permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupClickListeners() {
        binding.appBlockerCard.setOnClickListener {
            if (!isAppBlockerOn) {
                showAccessibilityDialog("App Blocker", AppBlockerService::class.java)
            }
        }

        binding.selectBlockedApps.setOnClickListener {
            val intent = Intent(requireContext(), nethical.digipaws.ui.activity.SelectAppBlockerApps::class.java)
            selectBlockedAppsLauncher.launch(intent)
        }

        binding.appBlockerChip.setOnClickListener {
            if (!isAppBlockerOn) {
                showAccessibilityDialog("App Blocker", AppBlockerService::class.java)
            }
        }

        binding.focusModeCard.setOnClickListener {
            if (!isAppBlockerOn) {
                showAccessibilityDialog("Focus Mode", AppBlockerService::class.java)
            }
        }

        binding.selectFocusApps.setOnClickListener {
            val intent = Intent(requireContext(), SelectAppsActivity::class.java)
            intent.putStringArrayListExtra(
                "PRE_SELECTED_APPS",
                ArrayList(savedPreferencesLoader.getFocusModeSelectedApps())
            )
            selectFocusAppsLauncher.launch(intent)
        }

        binding.startFocusMode.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return@setOnClickListener
                }
            }
            showStartFocusModeDialog()
        }

        binding.autoFocus.setOnClickListener {
            val intent = Intent(requireContext(), TimedActionActivity::class.java)
            intent.putExtra("selected_mode", TimedActionActivity.MODE_AUTO_FOCUS)
            addAutoFocusHoursLauncher.launch(intent)
        }

        binding.focusModeChip.setOnClickListener {
            if (!isAppBlockerOn) {
                showAccessibilityDialog("Focus Mode", AppBlockerService::class.java)
            }
        }

        binding.grayscaleCard.setOnClickListener {
            if (!isGeneralSettingsOn) {
                showAccessibilityDialog("General Features", GeneralFeaturesService::class.java)
                return@setOnClickListener
            }
            checkShizukuAndProceed()
        }

        binding.selectGrayscaleApps.setOnClickListener {
            val intent = Intent(requireContext(), SelectAppsActivity::class.java)
            intent.putStringArrayListExtra(
                "PRE_SELECTED_APPS",
                ArrayList(savedPreferencesLoader.loadGrayScaleApps())
            )
            selectGrayscaleAppsLauncher.launch(intent)
        }

        binding.setupGrayscale.setOnClickListener {
            TweakGrayScaleMode(savedPreferencesLoader).show(
                childFragmentManager,
                "tweak_gray_scale"
            )
        }

        binding.grayscaleChip.setOnClickListener {
            if (!isGeneralSettingsOn) {
                showAccessibilityDialog("General Features", GeneralFeaturesService::class.java)
                return@setOnClickListener
            }
            checkShizukuAndProceed()
        }
    }

    private fun showStartFocusModeDialog() {
        StartFocusMode(savedPreferencesLoader, onPositiveButtonPressed = {
            binding.selectFocusApps.isEnabled = false
            binding.startFocusMode.isEnabled = false
        }).show(childFragmentManager, "start_focus_mode")
    }

    private fun checkShizukuAndProceed() {
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(0)
            }
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Shizuku Required")
                .setMessage("Shizuku is required for the grayscale filter feature. Would you like to download it?")
                .setPositiveButton("Download") { _, _ ->
                    openShizukuDownload()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun openShizukuDownload() {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/"))
        startActivity(intent)
    }

    private fun showAccessibilityDialog(title: String, cls: Class<*>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.enable_2, title))
            .setMessage(getString(R.string.accessibility_permission_app_blocker))
            .setPositiveButton("Enable") { _, _ ->
                openAccessibilityServiceScreen(cls)
            }
            .setNeutralButton("Guide") { _, _ ->
                // Open guide
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
            isAppBlockerOn = withContext(Dispatchers.IO) {
                isAccessibilityServiceEnabled(AppBlockerService::class.java)
            }
            isGeneralSettingsOn = withContext(Dispatchers.IO) {
                isAccessibilityServiceEnabled(GeneralFeaturesService::class.java)
            }

            updateUI()
            checkShizukuPermission()
        }
    }

    private fun checkShizukuPermission() {
        if (Shizuku.pingBinder()) {
            isShizukuOn = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            updateShizukuUI()
        }
    }

    private fun updateUI() {
        updateChip(isAppBlockerOn, binding.appBlockerChip)
        binding.selectBlockedApps.isEnabled = isAppBlockerOn

        if (!isAppBlockerOn) {
            binding.appBlockerWarning.visibility = View.VISIBLE
            binding.appBlockerWarning.text = getString(R.string.warning_app_blocker_settings)
        } else {
            binding.appBlockerWarning.visibility = View.GONE
        }

        updateChip(isAppBlockerOn, binding.focusModeChip)
        binding.selectFocusApps.isEnabled = isAppBlockerOn
        binding.startFocusMode.isEnabled = isAppBlockerOn
        binding.autoFocus.isEnabled = isAppBlockerOn

        if (!isAppBlockerOn) {
            binding.focusModeWarning.visibility = View.VISIBLE
            binding.focusModeWarning.text = getString(R.string.warning_app_blocker_settings)
        } else {
            binding.focusModeWarning.visibility = View.GONE
        }

        if (!isGeneralSettingsOn) {
            binding.grayscaleChip.text = getString(R.string.disabled)
            binding.grayscaleChip.setChipIconResource(R.drawable.baseline_warning_24)
            binding.grayscaleChip.chipIconTint = ContextCompat.getColorStateList(requireContext(), R.color.error_color)
        }
    }

    private fun updateShizukuUI() {
        if (isShizukuOn) {
            binding.grayscaleChip.text = getString(R.string.enabled)
            binding.grayscaleChip.chipIcon = null
            binding.selectGrayscaleApps.isEnabled = true
            binding.setupGrayscale.isEnabled = true
        } else {
            binding.grayscaleChip.text = getString(R.string.disabled)
            binding.grayscaleChip.setChipIconResource(R.drawable.baseline_warning_24)
            binding.grayscaleChip.chipIconTint = ContextCompat.getColorStateList(requireContext(), R.color.error_color)
            binding.selectGrayscaleApps.isEnabled = false
            binding.setupGrayscale.isEnabled = false
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
