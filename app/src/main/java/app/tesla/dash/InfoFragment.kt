package app.tesla.dash

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.content.FileProvider.getUriForFile
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import app.tesla.dash.databinding.FragmentInfoBinding
import java.io.File


class InfoFragment : Fragment() {
    private val TAG = InfoFragment::class.java.simpleName
    private lateinit var binding: FragmentInfoBinding
    private lateinit var viewModel: DashViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity()).get(DashViewModel::class.java)

        val options = viewModel.getCANServiceOptions(requireContext())
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, options)
        binding.chooseService.adapter = adapter
        binding.chooseService.setSelection(viewModel.getCurrentCANServiceIndex())

        binding.editIpAddress.text = SpannableStringBuilder(viewModel.serverIpAddress())

        binding.saveButton.setOnClickListener {
            viewModel.saveSettings(
                getSelectedCANServiceIndex(),
                binding.editIpAddress.text.toString()
            )
            reload()
        }
        binding.startButton.setOnClickListener {
            if (!viewModel.isRunning()) {
                viewModel.startUp()
            }
        }

        binding.stopButton.setOnClickListener {
            if (viewModel.isRunning()) {
                viewModel.shutdown()
            }
        }
        binding.settings.setOnClickListener {
            switchToSettings()
        }
        binding.root.setOnLongClickListener {
            switchToDash()
        }
        binding.startDashButton.setOnClickListener {
            switchToDash()
        }

        binding.scrollView.setOnLongClickListener {
            switchToDash()
        }

        binding.trash.setOnClickListener {
            viewModel.clearCarState()
        }

        viewModel.onAllSignals(viewLifecycleOwner) {
            binding.infoText.text = buildSpannedString {
                // This might fail when hitting the trashcan button due to a race condition
                val sortedMap = try {
                    it.toSortedMap()
                } catch (exception: ConcurrentModificationException) {
                    sortedMapOf()
                }
                sortedMap.forEach { entry ->
                    bold {
                        append(entry.key)
                        append(": ")
                    }
                    append(entry.value.toString())
                    append("\n")
                }
            }
        }
    }

    private fun getSelectedCANServiceIndex(): Int {
        return binding.chooseService.selectedItemPosition
    }

    private fun setupZeroConfListener() {
        viewModel.zeroConfIpAddress.observe(viewLifecycleOwner) { ipAddress ->
            if (viewModel.serverIpAddress() != ipAddress && !ipAddress.equals("0.0.0.0")) {
                viewModel.saveSettings(ipAddress)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupZeroConfListener()
        viewModel.startDiscoveryService()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopDiscoveryService()

    }

    private fun reload(): Boolean {
        viewModel.switchToInfoFragment()
        return true
    }

    private fun switchToDash(): Boolean {
        viewModel.switchToDashFragment()
        return true
    }

    private fun switchToSettings(): Boolean {
        viewModel.switchToSettingsFragment()
        return true
    }
}
