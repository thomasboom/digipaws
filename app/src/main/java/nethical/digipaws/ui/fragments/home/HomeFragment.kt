package nethical.digipaws.ui.fragments.home

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.icu.util.Calendar
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.app.usage.UsageStatsManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.animation.Easing
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nethical.digipaws.R
import nethical.digipaws.databinding.AppUsageItemBinding
import nethical.digipaws.databinding.DialogPermissionInfoBinding
import nethical.digipaws.databinding.FragmentHomeBinding
import nethical.digipaws.ui.activity.FragmentActivity
import nethical.digipaws.ui.fragments.usage.AllAppsUsageFragment
import nethical.digipaws.utils.SavedPreferencesLoader
import nethical.digipaws.utils.TimeTools
import nethical.digipaws.utils.UsageStatsHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var selectedDate: Long = System.currentTimeMillis()
    private var currentDate: Long = selectedDate
    private var earliestDate: Long = selectedDate

    private var ignoredPackages: MutableSet<String> = mutableSetOf()
    private lateinit var savedPreferencesLoader: SavedPreferencesLoader

    private lateinit var adapter: AppUsageAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedPreferencesLoader = SavedPreferencesLoader(requireContext())

        if (!hasUsageStatsPermission(requireContext())) {
            makeUsageStatsPermissionDialog()
        }

        setupRecyclerView()
        setupClickListeners()

        lifecycleScope.launch(Dispatchers.IO) {
            getDefaultLauncherPackageName(requireContext().packageManager)?.let {
                ignoredPackages.add(it)
            }
            ignoredPackages.addAll(savedPreferencesLoader.loadIgnoredAppUsageTracker())
            findDataAvailabilityRange()
            loadUsageStats()
        }
    }

    private fun setupRecyclerView() {
        adapter = AppUsageAdapter(emptyList())
        binding.appUsageRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.appUsageRecyclerView.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.selectDate.setOnClickListener {
            showDatePicker()
        }

        binding.viewAllApps.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java)
            intent.putExtra("fragment", AllAppsUsageFragment.FRAGMENT_ID)
            startActivity(intent)
        }
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = selectedDate

        val datePicker = DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val pickedCalendar = Calendar.getInstance()
                pickedCalendar.set(year, month, dayOfMonth)
                selectedDate = pickedCalendar.timeInMillis

                val localDate = Instant.ofEpochMilli(selectedDate)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()

                binding.selectDate.text = TimeTools.formatDate(selectedDate)

                lifecycleScope.launch(Dispatchers.IO) {
                    loadUsageStats(localDate)
                }
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        datePicker.datePicker.minDate = earliestDate
        datePicker.datePicker.maxDate = currentDate
        datePicker.show()
    }

    fun findDataAvailabilityRange() {
        val usageStatsManager = requireContext().getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            0,
            System.currentTimeMillis()
        )

        earliestDate = stats.minOfOrNull { stat -> stat.firstTimeStamp } ?: System.currentTimeMillis()
        currentDate = System.currentTimeMillis()
        selectedDate = currentDate.coerceAtLeast(earliestDate)
    }

    private suspend fun loadUsageStats(date: LocalDate = LocalDate.now()) {
        val usageStatsHelper = UsageStatsHelper(requireContext())
        val list = usageStatsHelper.getForegroundStatsByDay(date).filter {
            it.totalTime >= 180_000 && it.packageName !in ignoredPackages
        }

        val totalTimeFormatted = TimeTools.formatTime(calculateTotalScreenTime(list), false)

        withContext(Dispatchers.Main) {
            if (list.isEmpty()) {
                binding.totalUsage.text = "0h 0m"
            } else {
                binding.totalUsage.text = totalTimeFormatted
            }

            updatePieChart(list)
            adapter.updateData(list.take(5))
        }
    }

    private fun calculateTotalScreenTime(stats: List<AllAppsUsageFragment.Stat>): Long {
        return stats.sumOf { it.totalTime }
    }

    private fun updatePieChart(statsList: List<AllAppsUsageFragment.Stat>) {
        val sortedStats = statsList.sortedByDescending { it.totalTime }
        val topApps = sortedStats.take(3)
        val othersTime = sortedStats.drop(3).sumOf { it.totalTime }

        val entries = mutableListOf<PieEntry>()
        val pm = requireContext().packageManager

        val colors = listOf(
            ContextCompat.getColor(requireContext(), R.color.md_theme_primary),
            ContextCompat.getColor(requireContext(), R.color.md_theme_secondary),
            ContextCompat.getColor(requireContext(), R.color.md_theme_tertiary),
            ContextCompat.getColor(requireContext(), R.color.md_theme_error)
        )

        topApps.forEachIndexed { index, stats ->
            try {
                val appInfo = pm.getApplicationInfo(stats.packageName, 0)
                val icon = appInfo.loadIcon(pm)
                entries.add(PieEntry(stats.totalTime.toFloat(), resizeIcon(icon, 24, 24)))
            } catch (e: Exception) {
                entries.add(PieEntry(stats.totalTime.toFloat(), ""))
            }
        }

        if (othersTime > 0) {
            entries.add(PieEntry(othersTime.toFloat(), ""))
        }

        val pieDataSet = PieDataSet(entries, "").apply {
            this.colors = colors
            sliceSpace = 2f
            setDrawValues(false)
            selectionShift = 8f
        }

        val pieData = PieData(pieDataSet)

        binding.pieChart.apply {
            data = pieData
            description.isEnabled = false
            isRotationEnabled = true
            isDrawHoleEnabled = true
            holeRadius = 75f
            transparentCircleRadius = 0f
            setHoleColor(ContextCompat.getColor(requireContext(), R.color.md_theme_surface))
            legend.isEnabled = false
            setDrawEntryLabels(false)
            animateY(1200, Easing.EaseInOutQuart)
            invalidate()
        }
    }

    private fun resizeIcon(icon: Drawable, width: Int, height: Int): Drawable {
        val bitmap = if (icon is BitmapDrawable) {
            icon.bitmap
        } else {
            val bitmap = Bitmap.createBitmap(
                icon.intrinsicWidth,
                icon.intrinsicHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            icon.setBounds(0, 0, canvas.width, canvas.height)
            icon.draw(canvas)
            bitmap
        }

        val density = resources.displayMetrics.density
        val targetWidth = (width * density).toInt()
        val targetHeight = (height * density).toInt()

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        return BitmapDrawable(resources, scaledBitmap)
    }

    private fun getDefaultLauncherPackageName(packageManager: PackageManager): String? {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.resolvePackageName
    }

    private fun makeUsageStatsPermissionDialog() {
        val dialogBinding = DialogPermissionInfoBinding.inflate(layoutInflater)
        dialogBinding.title.text = getString(R.string.enable_2, "Device Usage Access")
        dialogBinding.desc.text = "DigiPaws requires device usage access to monitor apps, helping you manage screen time effectively. All data stays securely on your device."
        dialogBinding.point1.text = "Track what apps you use"
        dialogBinding.point2.visibility = View.GONE

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setCancelable(false)
            .show()

        dialogBinding.btnReject.setOnClickListener {
            dialog.dismiss()
            activity?.finish()
        }
        dialogBinding.btnAccept.setOnClickListener {
            Toast.makeText(requireContext(), "Find 'Digipaws' and press enable", Toast.LENGTH_LONG)
                .show()
            requestUsageStatsPermission(requireContext())
            dialog.dismiss()
        }
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOpsManager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun requestUsageStatsPermission(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    inner class AppUsageViewHolder(private val binding: AppUsageItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(stats: AllAppsUsageFragment.Stat) {
            val pm = binding.root.context.packageManager
            try {
                val appInfo = pm.getApplicationInfo(stats.packageName, 0)
                binding.appIcon.setImageDrawable(appInfo.loadIcon(pm))
                binding.appName.text = appInfo.loadLabel(pm)
            } catch (e: Exception) {
                binding.appIcon.setImageResource(R.drawable.baseline_warning_24)
                binding.appName.text = stats.packageName
            }
            binding.appUsage.text = TimeTools.formatTime(stats.totalTime)
        }
    }

    inner class AppUsageAdapter(
        private var appUsageStats: List<AllAppsUsageFragment.Stat>
    ) : RecyclerView.Adapter<AppUsageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppUsageViewHolder {
            val binding = AppUsageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return AppUsageViewHolder(binding)
        }

        override fun onBindViewHolder(holder: AppUsageViewHolder, position: Int) {
            holder.bind(appUsageStats[position])
        }

        @Suppress("NotifyDataSetChanged")
        fun updateData(newAppUsageStats: List<AllAppsUsageFragment.Stat>) {
            appUsageStats = newAppUsageStats
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = appUsageStats.size
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) {
            loadUsageStats()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
