package app.tesla.dash

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.GradientDrawable.Orientation
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.doOnEnd
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import app.tesla.dash.databinding.FragmentDashBinding
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

class DashFragment : Fragment() {
    private lateinit var binding: FragmentDashBinding
    private lateinit var viewModel: DashViewModel
    private lateinit var unitConverter: UnitConverter
    private lateinit var prefs: SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?): View {

        binding = FragmentDashBinding.inflate(inflater, container, false)
        prefs = requireContext().getSharedPreferences("dash", Context.MODE_PRIVATE)
        setupWebView()
        return binding.root
    }

    private fun setupWebView() {
        binding.dashWebView.loadUrl("file:///android_asset/dashboard.html")
        binding.dashWebView.settings.javaScriptEnabled = true
    }

    private fun setupWebViewListeners() {
        fun runJS(js: String) {
            binding.dashWebView.evaluateJavascript(js, null)
        }
        fun setIndicatorStatus(indicator: String, status: Boolean) {
            runJS("setIndicatorStatus($indicator, $status)")
        }
        fun setLightStatus(status: Int) {
            runJS("setLightStatus($status)")
        }
        fun setDashFeature(side: String, label: String, value: String, unit: String) {
            runJS("setDashFeature($side, $label, $value, $unit)")
        }
        fun setSubDisplayStripEntry(number: Int, label: String, value: String, unit: String) {
            runJS("setSubDisplayStripEntry($number, $label, $value, $unit)")
        }
        fun setBrakeOverlay(isPressed: Boolean) {
            runJS("setBrakeOverlay($isPressed)")
        }
        fun setNightMode(isNightMode: Boolean) {
            runJS("setNightMode(true)") // TODO: Always night mode
        }
        fun setSportMode(isSportMode: Boolean) {
            runJS("setSportMode(false)") // TODO: Always non-sport mode
        }
        // Speed:
        viewModel.onSomeSignals(viewLifecycleOwner, listOf(SName.uiSpeed, SName.brakeHold)) {
            val speed = it[SName.uiSpeed]
            if (speed != null) {
                if (it[SName.brakeHold] == 1f) {
                    runJS("setSpeed(0)")
                } else {
                    runJS("setSpeed($speed)")
                }
            } else {
                runJS("setSpeed(0)")
            }
        }
        // Power:
        viewModel.onSignal(viewLifecycleOwner, SName.power) {
            if (it != null) {
                val powerKW = it / 1000
                runJS("setPower($powerKW)")
            }
        }
        // Battery:
        viewModel.onSomeSignals(viewLifecycleOwner, listOf(SName.uiRange, SName.stateOfCharge, SName.chargeStatus)) {
            setIndicatorStatus("indicatorCharging", carIsCharging())
            it[SName.stateOfCharge]?.let { soc ->
                setSubDisplayStripEntry(3, "battery", soc.roundToInt().toString(), "%")
            }
        }
        // Outside temp:
        viewModel.onSignal(viewLifecycleOwner, SName.outsideTemp) {
            it?.let {
                setDashFeature("left", "outside", it.toString(), "°C")
            }
        }
        // Turn signals:
        viewModel.onSignal(viewLifecycleOwner, SName.turnSignalLeft) {
            if (it != null) {
                setIndicatorStatus("indicatorBlinkerLeft", it > 1f)
            }
        }
        viewModel.onSignal(viewLifecycleOwner, SName.turnSignalRight) {
            if (it != null) {
                setIndicatorStatus("indicatorBlinkerRight", it > 1f)
            }
        }
        // Selected gear:
        viewModel.onSignal(viewLifecycleOwner, SName.gearSelected) {
            if (it != null) {
                val gearLetter = when (it) {
                    SVal.gearPark -> 'P'
                    SVal.gearReverse -> 'R'
                    SVal.gearNeutral -> 'N'
                    SVal.gearDrive -> 'D'
                    else -> 'P'
                }
                runJS("setGear('$gearLetter')")
            }
        }
        // Battery heating:
        viewModel.onSignal(viewLifecycleOwner, SName.heatBattery) {
            if (it != null) {
                setIndicatorStatus("indicatorBatteryHeating", it == 1f)
            }
        }
        // Regen limited:
        viewModel.onSignal(viewLifecycleOwner, SName.limRegen) {
            if (it != null) {
                setIndicatorStatus("indicatorRegenLimited", it == 1f)
            }
        }
        // Night mode:
        viewModel.onSignal(viewLifecycleOwner, SName.isSunUp) {
            it?.let {
                setNightMode(it != 1f)
            }
        }
        // Drive mode (normal/sport):
        viewModel.onSignal(viewLifecycleOwner, SName.driveMode) {
            it?.let {
                setSportMode(it == 1f)
            }
        }
        // Seatbelt status:
        viewModel.onSomeSignals(viewLifecycleOwner, listOf(SName.driverUnbuckled, SName.passengerUnbuckled)) {
            val seatbeltLightOn = (it[SName.driverUnbuckled] == 1f) || (it[SName.passengerUnbuckled] == 1f)
            setIndicatorStatus("indicatorSeatbelt", seatbeltLightOn)
        }
        // Parking brake:
        viewModel.onSignal(viewLifecycleOwner, SName.brakePark) {
            if (it != null) {
                setIndicatorStatus("indicatorParkingBrake", it == SVal.brakeParkRed)
            }
        }
        // Brake pressed:
        viewModel.onSignal(viewLifecycleOwner, SName.brakeApplied) {
            it?.let {
                setBrakeOverlay(it == 1f)
            }
        }
        // Lights:
        viewModel.onSomeSignals(viewLifecycleOwner, SGroup.lights) {
            // Set front and rear fog lights:
            setIndicatorStatus("indicatorFogLightsFront", viewModel.carState[SName.frontFogStatus] == 1f)
            setIndicatorStatus("indicatorFogLightsRear", viewModel.carState[SName.rearFogStatus] == 1f)
            val lightingState = viewModel.carState[SName.lightingState]
            if (lightingState != null) {
                val lightStatus = when (lightingState) {
                    4f -> 2
                    1f, 2f, 3f -> 1
                    0f -> 0
                    else -> 0
                }
                setLightStatus(lightStatus)
            }

            // High beams:
            if (viewModel.carState[SName.highBeamStatus] == 1f) {
                // High beams are ON
                if (viewModel.carState[SName.highBeamRequest] == SVal.highBeamAuto) {
                    // Auto high beams are ON
                    // High beams are ON
                    setLightStatus(5)
                } else {
                    // Auto high beams are OFF
                    // High beams are ON
                    setLightStatus(3)
                }
            } else {
                if (viewModel.carState[SName.autoHighBeamEnabled] == 1f
                    && viewModel.carState[SName.highBeamRequest] == SVal.highBeamAuto) {
                    // Auto high beams are ON
                    // High beams are OFF
                    setLightStatus(4)
                } else {
                    // Auto high beams are OFF
                    // High beams are OFF
                }
            }
        }
        // Battery temp:
        viewModel.onSignal(viewLifecycleOwner, SName.battBrickMin) {
            if (it != null) {
                runJS("setBatteryTemp($it)")
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = requireContext().getSharedPreferences("dash", Context.MODE_PRIVATE)
        unitConverter = UnitConverter(prefs)
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity()).get(DashViewModel::class.java)

        setupWebViewListeners()

        binding.root.setOnLongClickListener {
            viewModel.switchToInfoFragment()
            return@setOnLongClickListener true
        }
        binding.dashWebView.setOnLongClickListener {
            viewModel.switchToInfoFragment()
            return@setOnLongClickListener true
        }

        val efficiencyCalculator = EfficiencyCalculator(viewModel, prefs)

        viewModel.onSignal(viewLifecycleOwner, SName.keepClimateReq) {
            if (it == SVal.keepClimateParty) {
                viewModel.switchToPartyFragment()
            }
        }

        viewModel.onSomeSignals(viewLifecycleOwner, listOf(SName.odometer, SName.gearSelected)) {
            efficiencyCalculator.updateKwhHistory()
        }
    }

    private fun carIsCharging(): Boolean {
        val chargeStatus = viewModel.carState[SName.chargeStatus] ?: SVal.chargeStatusInactive
        return chargeStatus != SVal.chargeStatusInactive
    }

    // If the discoveryService finds a different ip address, save the new
    // address and restart
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
        binding.root.postDelayed({
            viewModel.startDiscoveryService()
        }, 2000)
    }

    override fun onDestroy() {
        viewModel.stopDiscoveryService()
        super.onDestroy()
    }

    override fun onPause() {
        viewModel.stopDiscoveryService()
        super.onPause()
    }
}