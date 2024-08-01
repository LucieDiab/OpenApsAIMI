package app.aaps.plugins.aps.openAPSAIMI

import android.os.Environment
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.UE
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences
import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork.Companion.refineSMB
import org.tensorflow.lite.Interpreter
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

@Singleton
class DetermineBasalaimiSMB2 @Inject constructor(
    private val profileUtil: ProfileUtil
) {
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var tddCalculator: TddCalculator
    @Inject lateinit var tirCalculator: TirCalculator
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var profileFunction: ProfileFunction
    private val consoleError = mutableListOf<String>()
    private val consoleLog = mutableListOf<String>()
    private val path = File(Environment.getExternalStorageDirectory().toString())
    private val modelFile = File(path, "AAPS/ml/model.tflite")
    private val modelFileUAM = File(path, "AAPS/ml/modelUAM.tflite")
    private val csvfile = File(path, "AAPS/oapsaimiML2_records.csv")
    private var predictedSMB = 0.0f
    private var variableSensitivity = 0.0f
    private var averageBeatsPerMinute = 0.0
    private var averageBeatsPerMinute10 = 0.0
    private var averageBeatsPerMinute60 = 0.0
    private var averageBeatsPerMinute180 = 0.0
    private var eventualBG = 0.0
    private var now = System.currentTimeMillis()
    private var iob = 0.0f
    private var cob = 0.0f
    private var predictedBg = 0.0f
    private var lastCarbAgeMin: Int = 0
    private var futureCarbs = 0.0f
    private var enablebasal: Boolean = false
    private var recentNotes: List<UE>? = null
    private var tags0to60minAgo = ""
    private var tags60to120minAgo = ""
    private var tags120to180minAgo = ""
    private var tags180to240minAgo = ""
    private var tir1DAYabove: Double = 0.0
    private var currentTIRLow: Double = 0.0
    private var currentTIRRange: Double = 0.0
    private var currentTIRAbove: Double = 0.0
    private var lastHourTIRLow: Double = 0.0
    private var lastHourTIRLow100: Double = 0.0
    private var lastHourTIRabove170: Double = 0.0
    private var lastHourTIRabove120: Double = 0.0
    private var bg = 0.0
    private var targetBg = 100.0f
    private var normalBgThreshold = 120.0f
    private var delta = 0.0f
    private var shortAvgDelta = 0.0f
    private var longAvgDelta = 0.0f
    private var lastsmbtime = 0
    private var acceleratingUp: Int = 0
    private var decceleratingUp: Int = 0
    private var acceleratingDown: Int = 0
    private var decceleratingDown: Int = 0
    private var stable: Int = 0
    private var maxIob = 0.0
    private var maxSMB = 1.0
    private var maxSMBHB = 1.0
    private var lastBolusSMBUnit = 0.0f
    private var tdd7DaysPerHour = 0.0f
    private var tdd2DaysPerHour = 0.0f
    private var tddPerHour = 0.0f
    private var tdd24HrsPerHour = 0.0f
    private var hourOfDay: Int = 0
    private var weekend: Int = 0
    private var recentSteps5Minutes: Int = 0
    private var recentSteps10Minutes: Int = 0
    private var recentSteps15Minutes: Int = 0
    private var recentSteps30Minutes: Int = 0
    private var recentSteps60Minutes: Int = 0
    private var recentSteps180Minutes: Int = 0
    private var basalaimi = 0.0f
    private var aimilimit = 0.0f
    private var ci = 0.0f
    private var sleepTime = false
    private var sportTime = false
    private var snackTime = false
    private var lowCarbTime = false
    private var highCarbTime = false
    private var mealTime = false
    private var bfastTime = false
    private var lunchTime = false
    private var dinnerTime = false
    private var fastingTime = false
    private var stopTime = false
    private var iscalibration = false
    private var mealruntime: Long = 0
    private var bfastruntime: Long = 0
    private var lunchruntime: Long = 0
    private var dinnerruntime: Long = 0
    private var highCarbrunTime: Long = 0
    private var snackrunTime: Long = 0
    private var intervalsmb = 5

    private fun Double.toFixed2(): String = DecimalFormat("0.00#").format(round(this, 2))

    private fun roundBasal(value: Double): Double = value

    // Rounds value to 'digits' decimal places
    // different for negative numbers fun round(value: Double, digits: Int): Double = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    fun round(value: Double, digits: Int): Double {
        if (value.isNaN()) return Double.NaN
        val scale = 10.0.pow(digits.toDouble())
        return Math.round(value * scale) / scale
    }

    private fun Double.withoutZeros(): String = DecimalFormat("0.##").format(this)
    fun round(value: Double): Int {
        if (value.isNaN()) return 0
        val scale = 10.0.pow(2.0)
        return (Math.round(value * scale) / scale).toInt()
    }

    // we expect BG to rise or fall at the rate of BGI,
    // adjusted by the rate at which BG would need to rise /
    // fall to get eventualBG to target over 2 hours
    // private fun calculateExpectedDelta(targetBg: Double, eventualBg: Double, bgi: Double): Double {
    //     // (hours * mins_per_hour) / 5 = how many 5 minute periods in 2h = 24
    //     val fiveMinBlocks = (2 * 60) / 5
    //     val targetDelta = targetBg - eventualBg
    //     return /* expectedDelta */ round(bgi + (targetDelta / fiveMinBlocks), 1)
    //}
    private fun calculateRate(basal: Double, currentBasal: Double, multiplier: Double, reason: String, currenttemp: CurrentTemp, rT: RT): Double {
        rT.reason.append("${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} $reason")
        return if (basal == 0.0) currentBasal * multiplier else roundBasal(basal * multiplier)
    }
    private fun calculateBasalRate(basal: Double, currentBasal: Double, multiplier: Double): Double =
        if (basal == 0.0) currentBasal * multiplier else roundBasal(basal * multiplier)

    private fun convertBG(value: Double): String =
        profileUtil.fromMgdlToStringInUnits(value).replace("-0.0", "0.0")

    private fun enablesmb(profile: OapsProfile, microBolusAllowed: Boolean, mealData: MealData, target_bg: Double): Boolean {
        // disable SMB when a high temptarget is set
        if (!microBolusAllowed) {
            consoleError.add("SMB disabled (!microBolusAllowed)")
            return false
        } else if (!profile.allowSMB_with_high_temptarget && profile.temptargetSet && target_bg > 100) {
            consoleError.add("SMB disabled due to high temptarget of $target_bg")
            return false
        }

        // enable SMB/UAM if always-on (unless previously disabled for high temptarget)
        if (profile.enableSMB_always) {
            consoleError.add("SMB enabled due to enableSMB_always")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) while we have COB
        if (profile.enableSMB_with_COB && mealData.mealCOB != 0.0) {
            consoleError.add("SMB enabled for COB of ${mealData.mealCOB}")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) for a full 6 hours after any carb entry
        // (6 hours is defined in carbWindow in lib/meal/total.js)
        if (profile.enableSMB_after_carbs && mealData.carbs != 0.0) {
            consoleError.add("SMB enabled for 6h after carb entry")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) if a low temptarget is set
        if (profile.enableSMB_with_temptarget && (profile.temptargetSet && target_bg < 100)) {
            consoleError.add("SMB enabled for temptarget of ${convertBG(target_bg)}")
            return true
        }

        consoleError.add("SMB disabled (no enableSMB preferences active or no condition satisfied)")
        return false
    }

    fun reason(rT: RT, msg: String) {
        if (rT.reason.toString().isNotEmpty()) rT.reason.append(". ")
        rT.reason.append(msg)
        consoleError.add(msg)
    }

    private fun getMaxSafeBasal(profile: OapsProfile): Double =
        min(profile.max_basal, min(profile.max_daily_safety_multiplier * profile.max_daily_basal, profile.current_basal_safety_multiplier * profile.current_basal))

    fun setTempBasal(_rate: Double, duration: Int, profile: OapsProfile, rT: RT, currenttemp: CurrentTemp): RT {
        val maxSafeBasal = getMaxSafeBasal(profile)
        var rate = _rate

        if (rate < 0) rate = 0.0
        else if (rate > maxSafeBasal) rate = maxSafeBasal

        val suggestedRate = roundBasal(rate)

        if (currenttemp.duration > (duration - 10) && currenttemp.duration <= 120 &&
            suggestedRate <= currenttemp.rate * 1.2 && suggestedRate >= currenttemp.rate * 0.8 &&
            duration > 0) {
            rT.reason.append(" ${currenttemp.duration}m left and ${currenttemp.rate.withoutZeros()} ~ req ${suggestedRate.withoutZeros()}U/hr: no temp required")
        } else if (suggestedRate == profile.current_basal) {
            if (profile.skip_neutral_temps) {
                if (currenttemp.duration > 0) {
                    reason(rT, "Suggested rate is same as profile rate, a temp basal is active, canceling current temp")
                    rT.duration = 0
                    rT.rate = 0.0
                } else {
                    reason(rT, "Suggested rate is same as profile rate, no temp basal is active, doing nothing")
                }
            } else {
                reason(rT, "Setting neutral temp basal of ${profile.current_basal}U/hr")
                rT.duration = duration
                rT.rate = suggestedRate
            }
        } else {
            rT.duration = duration
            rT.rate = suggestedRate
        }
        return rT
    }

    private fun logDataMLToCsv(predictedSMB: Float, smbToGive: Float) {

        val usFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm")
        val dateStr = dateUtil.dateAndTimeString(dateUtil.now()).format(usFormatter)


        val headerRow = "dateStr, bg, iob, cob, delta, shortAvgDelta, longAvgDelta, tdd7DaysPerHour, tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour, predictedSMB, smbGiven\n"
        val valuesToRecord = "$dateStr," +
            "$bg,$iob,$cob,$delta,$shortAvgDelta,$longAvgDelta," +
            "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
            "$predictedSMB,$smbToGive"

        val file = File(path, "AAPS/oapsaimiML2_records.csv")
        if (!file.exists()) {
            file.createNewFile()
            file.appendText(headerRow)
        }
        file.appendText(valuesToRecord + "\n")
    }
    private fun logDataToCsv(predictedSMB: Float, smbToGive: Float) {

        val usFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm")
        val dateStr = dateUtil.dateAndTimeString(dateUtil.now()).format(usFormatter)


        val headerRow = "dateStr,dateLong,hourOfDay,weekend," +
            "bg,targetBg,iob,cob,lastCarbAgeMin,futureCarbs,delta,shortAvgDelta,longAvgDelta," +
            "tdd7DaysPerHour,tdd2DaysPerHour,tddPerHour,tdd24HrsPerHour," +
            "recentSteps5Minutes,recentSteps10Minutes,recentSteps15Minutes,recentSteps30Minutes,recentSteps60Minutes,recentSteps180Minutes," +
            "tags0to60minAgo,tags60to120minAgo,tags120to180minAgo,tags180to240minAgo," +
            "predictedSMB,maxIob,maxSMB,smbGiven\n"
        val valuesToRecord = "$dateStr,$hourOfDay,$weekend," +
            "$bg,$targetBg,$iob,$cob,$lastCarbAgeMin,$futureCarbs,$delta,$shortAvgDelta,$longAvgDelta," +
            "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
            "$recentSteps5Minutes,$recentSteps10Minutes,$recentSteps15Minutes,$recentSteps30Minutes,$recentSteps60Minutes,$recentSteps180Minutes," +
            "$tags0to60minAgo,$tags60to120minAgo,$tags120to180minAgo,$tags180to240minAgo," +
            "$predictedSMB,$maxIob,$maxSMB,$smbToGive"

        val file = File(path, "AAPS/oapsaimi_records.csv")
        if (!file.exists()) {
            file.createNewFile()
            file.appendText(headerRow)
        }
        file.appendText(valuesToRecord + "\n")
    }

    private fun logDataToCsvHB(predictedSMB: Float, smbToGive: Float) {
        val dateStr = dateUtil.dateAndTimeString(dateUtil.now())

        val headerRow = "dateStr,dateLong,hourOfDay,weekend," +
            "bg,targetBg,iob,cob,lastCarbAgeMin,futureCarbs,delta,shortAvgDelta,longAvgDelta," +
            "accelerating_up,deccelerating_up,accelerating_down,deccelerating_down,stable," +
            "tdd7DaysPerHour,tdd2DaysPerHour,tddDailyPerHour,tdd24HrsPerHour," +
            "recentSteps5Minutes,recentSteps10Minutes,recentSteps15Minutes,recentSteps30Minutes,recentSteps60Minutes,averageBeatsPerMinute, averageBeatsPerMinute180," +
            "tags0to60minAgo,tags60to120minAgo,tags120to180minAgo,tags180to240minAgo," +
            "variableSensitivity,lastbolusage,predictedSMB,maxIob,maxSMB,smbGiven\n"
        val valuesToRecord = "$dateStr,${dateUtil.now()},$hourOfDay,$weekend," +
            "$bg,$targetBg,$iob,$cob,$lastCarbAgeMin,$futureCarbs,$delta,$shortAvgDelta,$longAvgDelta," +
            "$acceleratingUp,$decceleratingUp,$acceleratingDown,$decceleratingDown,$stable," +
            "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
            "$recentSteps5Minutes,$recentSteps10Minutes,$recentSteps15Minutes,$recentSteps30Minutes,$recentSteps60Minutes,$recentSteps180Minutes," +
            "$averageBeatsPerMinute, $averageBeatsPerMinute180," +
            "$tags0to60minAgo,$tags60to120minAgo,$tags120to180minAgo,$tags180to240minAgo," +
            "$variableSensitivity,$predictedSMB,$maxIob,$maxSMB,$smbToGive"

        val file = File(path, "AAPS/oapsaimiHB_records.csv")
        if (!file.exists()) {
            file.createNewFile()
            file.appendText(headerRow)
        }
        file.appendText(valuesToRecord + "\n")
    }
    private fun applySafetyPrecautions(glucoseStatus: GlucoseStatus, smbToGiveParam: Float): Float {
        var smbToGive = smbToGiveParam
        val (conditionResult, _) = isCriticalSafetyCondition()
        if (conditionResult) return 0.0f

        if (isSportSafetyCondition()) return 0.0f
        // Ajustements basés sur des conditions spécifiques
        smbToGive = applySpecificAdjustments(glucoseStatus,smbToGive)

        smbToGive = finalizeSmbToGive(smbToGive)
        // Appliquer les limites maximum
        smbToGive = applyMaxLimits(smbToGive)

        return smbToGive
    }
    private fun applyMaxLimits(smbToGive: Float): Float {
        var result = smbToGive

        // Vérifiez d'abord si smbToGive dépasse maxSMB
        if (result > maxSMB) {
            result = maxSMB.toFloat()
        }
        // Ensuite, vérifiez si la somme de iob et smbToGive dépasse maxIob
        if (iob + result > maxIob) {
            result = maxIob.toFloat() - iob
        }

        return result
    }

    private fun isMealModeCondition(): Boolean {
        val pbolusM: Double = preferences.get(DoubleKey.OApsAIMIMealPrebolus)
        return mealruntime in 0..7 && lastBolusSMBUnit != pbolusM.toFloat() && mealTime
    }
    private fun isbfastModeCondition(): Boolean {
        val pbolusbfast: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus)
        return bfastruntime in 0..7 && lastBolusSMBUnit != pbolusbfast.toFloat() && bfastTime
    }
    private fun isbfast2ModeCondition(): Boolean {
        val pbolusbfast2: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus2)
        return bfastruntime in 15..30 && lastBolusSMBUnit != pbolusbfast2.toFloat() && bfastTime
    }
    private fun isLunchModeCondition(): Boolean {
        val pbolusLunch: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus)
        return lunchruntime in 0..7 && lastBolusSMBUnit != pbolusLunch.toFloat() && lunchTime
    }
    private fun isLunch2ModeCondition(): Boolean {
        val pbolusLunch2: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus2)
        return lunchruntime in 15..24 && lastBolusSMBUnit != pbolusLunch2.toFloat() && lunchTime
    }
    private fun isDinnerModeCondition(): Boolean {
        val pbolusDinner: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus)
        return dinnerruntime in 0..7 && lastBolusSMBUnit != pbolusDinner.toFloat() && dinnerTime
    }
    private fun isDinner2ModeCondition(): Boolean {
        val pbolusDinner2: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus2)
        return dinnerruntime in 15..24 && lastBolusSMBUnit != pbolusDinner2.toFloat() && dinnerTime
    }
    private fun isHighCarbModeCondition(): Boolean {
        val pbolusHC: Double = preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus)
        return highCarbrunTime in 0..7 && lastBolusSMBUnit != pbolusHC.toFloat() && highCarbTime
    }

    private fun issnackModeCondition(): Boolean {
        val pbolussnack: Double = preferences.get(DoubleKey.OApsAIMISnackPrebolus)
        return snackrunTime in 0..7 && lastBolusSMBUnit != pbolussnack.toFloat() && snackTime
    }
    private fun roundToPoint05(number: Float): Float {
        return (number * 20.0).roundToInt() / 20.0f
    }
    private fun isCriticalSafetyCondition(): Pair<Boolean, String> {
        val conditionsTrue = mutableListOf<String>()
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        val nosmbHM = iob > 0.7 && honeymoon && delta < 8 && !mealTime && !bfastTime && !lunchTime && !dinnerTime && eventualBG < 130
        if (nosmbHM) conditionsTrue.add("nosmbHM")
        val nosmb = iob >= 2*maxSMB && bg < 120 && delta < 10 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (nosmb) conditionsTrue.add("nosmb")
        val fasting = fastingTime
        if (fasting) conditionsTrue.add("fasting")
        val belowMinThreshold = bg < 130 && delta < 10 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (belowMinThreshold) conditionsTrue.add("belowMinThreshold")
        val isNewCalibration = iscalibration && delta > 10
        if (isNewCalibration) conditionsTrue.add("isNewCalibration")
        val belowTargetAndDropping = bg < targetBg && delta < -2 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (belowTargetAndDropping) conditionsTrue.add("belowTargetAndDropping")
        val belowTargetAndStableButNoCob = bg < targetBg - 15 && shortAvgDelta <= 2 && cob <= 10 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (belowTargetAndStableButNoCob) conditionsTrue.add("belowTargetAndStableButNoCob")
        val droppingFast = bg < 160 && delta < -5
        if (droppingFast) conditionsTrue.add("droppingFast")
        val droppingFastAtHigh = bg < 220 && delta <= -7
        if (droppingFastAtHigh) conditionsTrue.add("droppingFastAtHigh")
        val droppingVeryFast = delta < -11
        if (droppingVeryFast) conditionsTrue.add("droppingVeryFast")
        val prediction = eventualBG < targetBg && bg < 135
        if (prediction) conditionsTrue.add("prediction")
        val interval = eventualBG < targetBg && delta > 10 && iob >= maxSMB/2 && lastsmbtime < 10
        if (interval) conditionsTrue.add("interval")
        val targetinterval = targetBg >= 120 && delta > 0 && iob >= maxSMB/2 && lastsmbtime < 12
        if (targetinterval) conditionsTrue.add("targetinterval")
        val stablebg = delta>-3 && delta<3 && shortAvgDelta>-3 && shortAvgDelta<3 && longAvgDelta>-3 && longAvgDelta<3 && bg < 120 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (stablebg) conditionsTrue.add("stablebg")
        val acceleratingDown = delta < -2 && delta - longAvgDelta < -2 && lastsmbtime < 15
        if (acceleratingDown) conditionsTrue.add("acceleratingDown")
        val decceleratingdown = delta < 0 && (delta > shortAvgDelta || delta > longAvgDelta) && lastsmbtime < 15
        if (decceleratingdown) conditionsTrue.add("decceleratingdown")
        val nosmbhoneymoon = honeymoon && iob > maxIob / 2 && delta < 0
        if (nosmbhoneymoon) conditionsTrue.add("nosmbhoneymoon")
        val bg90 = bg < 90
        if (bg90) conditionsTrue.add("bg90")
        val result = belowTargetAndDropping || belowTargetAndStableButNoCob || nosmbHM ||
            droppingFast || droppingFastAtHigh || droppingVeryFast || prediction || interval || targetinterval || bg90 ||
            fasting || nosmb || isNewCalibration || stablebg || belowMinThreshold || acceleratingDown || decceleratingdown || nosmbhoneymoon

        val conditionsTrueString = if (conditionsTrue.isNotEmpty()) {
            conditionsTrue.joinToString(", ")
        } else {
            "No conditions met"
        }

        return Pair(result, conditionsTrueString)
    }
    private fun isSportSafetyCondition(): Boolean {
        val sport = targetBg >= 140 && recentSteps5Minutes >= 200 && recentSteps10Minutes >= 500
        val sport1 = targetBg >= 140 && recentSteps5Minutes >= 200 && averageBeatsPerMinute > averageBeatsPerMinute60
        val sport2 = recentSteps5Minutes >= 200 && averageBeatsPerMinute > averageBeatsPerMinute60
        val sport3 = recentSteps5Minutes >= 200 && recentSteps10Minutes >= 500
        val sport4 = targetBg >= 140
        val sport5= sportTime

        return sport || sport1 || sport2 || sport3 || sport4 || sport5

    }
    private fun applySpecificAdjustments(glucoseStatus: GlucoseStatus, smbToGive: Float): Float {
        var result = smbToGive
        val intervalSMBsnack = preferences.get(IntKey.OApsAIMISnackinterval)
        val intervalSMBmeal = preferences.get(IntKey.OApsAIMImealinterval)
        val intervalSMBbfast = preferences.get(IntKey.OApsAIMIBFinterval)
        val intervalSMBlunch = preferences.get(IntKey.OApsAIMILunchinterval)
        val intervalSMBFCL = preferences.get(IntKey.OApsAIMIFCLinterval)
        val intervalSMBdinner = preferences.get(IntKey.OApsAIMIDinnerinterval)
        val intervalSMBsleep = preferences.get(IntKey.OApsAIMISleepinterval)
        val intervalSMBhc = preferences.get(IntKey.OApsAIMIHCinterval)
        val intervalSMBhighBG = preferences.get(IntKey.OApsAIMIHighBGinterval)
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        val belowTargetAndDropping = bg < targetBg
        val night = preferences.get(BooleanKey.OApsAIMInight)


        when {
            shouldApplyIntervalAdjustment(glucoseStatus, intervalSMBsnack, intervalSMBmeal, intervalSMBFCL, intervalSMBbfast, intervalSMBlunch, intervalSMBdinner, intervalSMBsleep, intervalSMBhc, intervalSMBhighBG) -> {
                result = 0.0f
            }
            shouldApplySafetyAdjustment() -> {
                result /= 2
                this.intervalsmb = 10
            }
            shouldApplyTimeAdjustment() -> {
                result = 0.0f
                this.intervalsmb = 10
            }
        }

        if (shouldApplyStepAdjustment()) result = 0.0f
        if (belowTargetAndDropping) result /= 2
        if (honeymoon && bg < 170 && delta < 5) result /= 2
        if (night && LocalTime.now().run { (hour in 23..23 || hour in 0..11) } && delta < 10 && iob < maxSMB) result /= 2

        return result
    }

    private fun shouldApplyIntervalAdjustment(glucoseStatus: GlucoseStatus, intervalSMBsnack: Int, intervalSMBmeal: Int,intervalSMBbfast: Int, intervalSMBFCL: Int, intervalSMBlunch: Int, intervalSMBdinner: Int, intervalSMBsleep: Int, intervalSMBhc:
    Int, intervalSMBhighBG:
    Int): Boolean {
        val historicalMealTimes = listOf(7, 8, 12, 13, 14, 19, 20, 21)
        //val isMealAnticipated = anticipateMeal(glucoseStatus, lastsmbtime, historicalMealTimes)
        return (lastsmbtime < intervalSMBsnack && snackTime) || (lastsmbtime < intervalSMBmeal && mealTime) || (lastsmbtime < intervalSMBbfast && bfastTime) || (lastsmbtime < intervalSMBlunch && lunchTime) || (lastsmbtime < intervalSMBdinner && dinnerTime) ||
            (lastsmbtime < intervalSMBsleep && sleepTime) || (lastsmbtime < intervalSMBhc && highCarbTime) //|| (lastsmbtime < intervalSMBhighBG && bg > 120 && !isMealAnticipated) || (lastsmbtime < intervalSMBFCL && isMealAnticipated)
    }

    private fun shouldApplySafetyAdjustment(): Boolean {
        val safetysmb = recentSteps180Minutes > 1500 && bg < 120
        return (safetysmb || lowCarbTime) && lastsmbtime >= 15
    }

    private fun shouldApplyTimeAdjustment(): Boolean {
        val safetysmb = recentSteps180Minutes > 1500 && bg < 120
        return (safetysmb || lowCarbTime) && lastsmbtime < 15
    }

    private fun shouldApplyStepAdjustment(): Boolean {
        return recentSteps5Minutes > 100 && recentSteps30Minutes > 500 && lastsmbtime < 20
    }
    private fun finalizeSmbToGive(smbToGive: Float): Float {
        var result = smbToGive
        // Assurez-vous que smbToGive n'est pas négatif
        if (result < 0.0f) {
            result = 0.0f
        }
        return result
    }
    private fun calculateSMBFromModel(): Float {
        val selectedModelFile: File?
        val modelInputs: FloatArray

        when {
            cob > 0 && lastCarbAgeMin < 240 && modelFile.exists() -> {
                selectedModelFile = modelFile
                modelInputs = floatArrayOf(
                    hourOfDay.toFloat(), weekend.toFloat(),
                    bg.toFloat(), targetBg, iob, cob, lastCarbAgeMin.toFloat(), futureCarbs, delta, shortAvgDelta, longAvgDelta
                )
            }

            modelFileUAM.exists()   -> {
                selectedModelFile = modelFileUAM
                modelInputs = floatArrayOf(
                    hourOfDay.toFloat(), weekend.toFloat(),
                    bg.toFloat(), targetBg, iob, delta, shortAvgDelta, longAvgDelta,
                    tdd7DaysPerHour, tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour,
                    recentSteps5Minutes.toFloat(),recentSteps10Minutes.toFloat(),recentSteps15Minutes.toFloat(),recentSteps30Minutes.toFloat(),recentSteps60Minutes.toFloat(),recentSteps180Minutes.toFloat()
                )
            }

            else                 -> {
                return 0.0F
            }
        }

        val interpreter = Interpreter(selectedModelFile)
        val output = arrayOf(floatArrayOf(0.0F))
        interpreter.run(modelInputs, output)
        interpreter.close()
        var smbToGive = output[0][0].toString().replace(',', '.').toDouble()

        val formatter = DecimalFormat("#.####", DecimalFormatSymbols(Locale.US))
        smbToGive = formatter.format(smbToGive).toDouble()

        return smbToGive.toFloat()
    }
    private fun neuralnetwork5(delta: Float, shortAvgDelta: Float, longAvgDelta: Float, predictedSMB: Float, profile: OapsProfile): Float {
        val minutesToConsider = 2500.0
        val linesToConsider = (minutesToConsider / 5).toInt()
        var totalDifference: Float
        val maxIterations = 10000.0
        var differenceWithinRange = false
        var finalRefinedSMB: Float = calculateSMBFromModel()
        val maxGlobalIterations = 5 // Nombre maximum d'itérations globales
        var globalConvergenceReached = false

        for (globalIteration in 1..maxGlobalIterations) {
            var globalIterationCount = 0
            var iterationCount = 0

            while (globalIterationCount < maxGlobalIterations && !globalConvergenceReached) {

                val allLines = csvfile.readLines()
                val headerLine = allLines.first()
                val headers = headerLine.split(",").map { it.trim() }
                val colIndices = listOf("bg", "iob", "cob", "delta", "shortAvgDelta", "longAvgDelta", "predictedSMB").map { headers.indexOf(it) }
                val targetColIndex = headers.indexOf("smbGiven")

                val lines = if (allLines.size > linesToConsider) allLines.takeLast(linesToConsider + 1) else allLines // +1 pour inclure l'en-tête

                val inputs = mutableListOf<FloatArray>()
                val targets = mutableListOf<DoubleArray>()
                var isAggressiveResponseNeeded = false
                for (line in lines.drop(1)) { // Ignorer l'en-tête
                    val cols = line.split(",").map { it.trim() }

                    val input = colIndices.mapNotNull { index -> cols.getOrNull(index)?.toFloatOrNull() }.toFloatArray()
                    // Calculez et ajoutez l'indicateur de tendance directement dans 'input'
                    val trendIndicator = calculateTrendIndicator(
                        delta, shortAvgDelta, longAvgDelta,
                        bg.toFloat(), iob, variableSensitivity, cob, normalBgThreshold,
                        recentSteps180Minutes, averageBeatsPerMinute.toFloat(), averageBeatsPerMinute10.toFloat(),
                        profile.insulinDivisor.toFloat(), recentSteps5Minutes, recentSteps10Minutes
                    )
                    val enhancedInput = input.copyOf(input.size + 1)
                    enhancedInput[input.size] = trendIndicator.toFloat()

                    val targetValue = cols.getOrNull(targetColIndex)?.toDoubleOrNull()
                    if (enhancedInput.size == colIndices.size + 1 && targetValue != null) {
                        inputs.add(enhancedInput)
                        targets.add(doubleArrayOf(targetValue))
                    }
                }

                if (inputs.isEmpty() || targets.isEmpty()) {
                    return predictedSMB
                }
                val epochsPerIteration = 10000
                val totalEpochs = 30000.0
                var learningRate = 0.001f // Default learning rate
                val decayFactor = 0.99f // For exponential decay
                val k = 5
                var neuralNetwork: AimiNeuralNetwork? = null
                val foldSize = inputs.size / k
                for (i in 0 until k) {
                    val validationInputs = inputs.subList(i * foldSize, (i + 1) * foldSize)
                    val validationTargets = targets.subList(i * foldSize, (i + 1) * foldSize)
                    val trainingInputs = inputs.minus(validationInputs)
                    val trainingTargets = targets.minus(validationTargets)

                    neuralNetwork = AimiNeuralNetwork(inputs.first().size, 5, 1)

                    // Training loop with learning rate decay
                    for (epoch in 10000..totalEpochs.toInt() step epochsPerIteration) {
                        for (innerEpoch in 1000 until epochsPerIteration) {
                            neuralNetwork.train(trainingInputs, trainingTargets, validationInputs, validationTargets, 10000, learningRate)
                            learningRate *= decayFactor // Exponential decay
                        }
                    }
                }

                do {
                    totalDifference = 0.0f

                    for (enhancedInput in inputs) {
                        val predictedrefineSMB = finalRefinedSMB// Prédiction du modèle TFLite
                        val refinedSMB = neuralNetwork?.let { refineSMB(predictedrefineSMB, it, enhancedInput) }
                        if (delta > 10 && bg > 100) {
                            isAggressiveResponseNeeded = true
                        }
                        val difference = abs(predictedrefineSMB - refinedSMB!!)
                        totalDifference += difference
                        if (difference in 0.0..2.5) {
                            finalRefinedSMB = if (refinedSMB > 0.0f) refinedSMB else 0.0f
                            differenceWithinRange = true
                            break
                        }
                    }
                    if (isAggressiveResponseNeeded && (finalRefinedSMB <= 0.5)) {
                        finalRefinedSMB = maxSMB.toFloat() / 2
                    }
                    iterationCount++
                    if (differenceWithinRange || iterationCount >= maxIterations) {
                        break
                    }
                } while (true)
                if (differenceWithinRange || iterationCount >= maxIterations) {
                    globalConvergenceReached = true
                }


                globalIterationCount++
            }
        }
        return if (globalConvergenceReached) finalRefinedSMB else predictedSMB
    }
    private fun calculateGFactor(delta: Float, lastHourTIRabove120: Double, bg: Float): Double {
        val deltaFactor = if (bg > 130) delta / 10 else 1.0f // Ajuster selon les besoins
        val bgFactor = if (bg > 150) 1.2 else if (bg < 130) 0.7 else 1.0

        // Introduire un facteur basé sur lastHourTIRabove170
        val tirFactor = if (bg > 140) 1.0 + lastHourTIRabove120 * 0.05 else 1.0 // Exemple: 5% d'augmentation pour chaque unité de lastHourTIRabove170

        // Combinez les facteurs pour obtenir un ajustement global
        return deltaFactor * bgFactor * tirFactor
    }
    private fun adjustFactorsBasedOnBgAndHypo(
        morningFactor: Float,
        afternoonFactor: Float,
        eveningFactor: Float
    ): Triple<Double, Double, Double> {
        val adjustedDelta = if (profileFunction.getUnits() == GlucoseUnit.MMOL) {
            delta * 18
        } else {
            delta
        }
        val hypoAdjustment = if (bg < 120 || (iob > 3 * maxSMB)) 0.8f else 1.0f
        val factorAdjustment = if (bg < 120) 0.1f else 0.2f
        val bgAdjustment = 1.0f + (ln(abs(adjustedDelta.toDouble()) + 1) - 1) * factorAdjustment
        val scalingFactor = 1.0f - (bg - targetBg).toFloat() / (140 - targetBg) * 0.5f
        val maxIncreaseFactor = 1.7f
        val maxDecreaseFactor = 0.7f // Limite la diminution à 30% de la valeur d'origine

        val adjustFactor = { factor: Float ->
            val adjustedFactor = factor * bgAdjustment * hypoAdjustment * scalingFactor
            adjustedFactor.coerceIn((factor * maxDecreaseFactor).toDouble(), (factor * maxIncreaseFactor).toDouble())
        }

        return Triple(
            adjustFactor(morningFactor),
            adjustFactor(afternoonFactor),
            adjustFactor(eveningFactor)
        )
    }

    private fun calculateTrendIndicator(
        delta: Float,
        shortAvgDelta: Float,
        longAvgDelta: Float,
        bg: Float,
        iob: Float,
        variableSensitivity: Float,
        cob: Float,
        normalBgThreshold: Float,
        recentSteps180Min: Int,
        averageBeatsPerMinute: Float,
        averageBeatsPerMinute10: Float,
        insulinDivisor: Float,
        recentSteps5min: Int,
        recentSteps10min: Int
    ): Int {
        // Calcul de l'impact de l'insuline
        val insulinEffect = calculateInsulinEffect(
            bg, iob, variableSensitivity, cob, normalBgThreshold, recentSteps180Min,
            averageBeatsPerMinute, averageBeatsPerMinute10, insulinDivisor
        )

        // Calcul de l'impact de l'activité physique
        val activityImpact = (recentSteps5min - recentSteps10min) * 0.05

        // Calcul de l'indicateur de tendance
        val trendValue = (delta * 0.5) + (shortAvgDelta * 0.25) + (longAvgDelta * 0.15) + (insulinEffect * 0.2) + (activityImpact * 0.1)

        return when {
            trendValue > 1.0 -> 1 // Forte tendance à la hausse
            trendValue < -1.0 -> -1 // Forte tendance à la baisse
            abs(trendValue) < 0.5 -> 0 // Pas de tendance significative
            trendValue > 0.5 -> 2 // Faible tendance à la hausse
            else -> -2 // Faible tendance à la baisse
        }
    }
    private fun calculateAdjustedDelayFactor(
        bg: Float,
        recentSteps180Minutes: Int,
        averageBeatsPerMinute: Float,
        averageBeatsPerMinute10: Float
    ): Float {
        if (bg.isNaN() || averageBeatsPerMinute.isNaN() || averageBeatsPerMinute10.isNaN() || averageBeatsPerMinute10 == 0f) {
            return 1f
        }

        val stepActivityThreshold = 1500
        val heartRateIncreaseThreshold = 1.2
        val insulinSensitivityDecreaseThreshold = 1.5 * normalBgThreshold

        val increasedPhysicalActivity = recentSteps180Minutes > stepActivityThreshold
        val heartRateChange = averageBeatsPerMinute / averageBeatsPerMinute10
        val increasedHeartRateActivity = heartRateChange >= heartRateIncreaseThreshold

        val baseFactor = when {
            bg <= normalBgThreshold -> 1f
            bg <= insulinSensitivityDecreaseThreshold -> 1f - ((bg - normalBgThreshold) / (insulinSensitivityDecreaseThreshold - normalBgThreshold))
            else -> 0.5f
        }

        return if (increasedPhysicalActivity || increasedHeartRateActivity) {
            (baseFactor.toFloat() * 0.8f).coerceAtLeast(0.5f)
        } else {
            baseFactor.toFloat()
        }
    }


    private fun calculateInsulinEffect(
        bg: Float,
        iob: Float,
        variableSensitivity: Float,
        cob: Float,
        normalBgThreshold: Float,
        recentSteps180Min: Int,
        averageBeatsPerMinute: Float,
        averageBeatsPerMinute10: Float,
        insulinDivisor: Float
    ): Float {
        // Calculer l'effet initial de l'insuline
        var insulinEffect = iob * variableSensitivity / insulinDivisor

        // Si des glucides sont présents, nous pourrions vouloir ajuster l'effet de l'insuline pour tenir compte de l'absorption des glucides.
        if (cob > 0) {
            // Ajustement hypothétique basé sur la présence de glucides. Ce facteur doit être déterminé par des tests/logique métier.
            insulinEffect *= 0.9f
        }
        val physicalActivityFactor = 1.0f - recentSteps180Min / 10000f
        insulinEffect *= physicalActivityFactor
        // Calculer le facteur de retard ajusté en fonction de l'activité physique
        val adjustedDelayFactor = calculateAdjustedDelayFactor(
            normalBgThreshold,
            recentSteps180Min,
            averageBeatsPerMinute,
            averageBeatsPerMinute10
        )

        // Appliquer le facteur de retard ajusté à l'effet de l'insuline
        insulinEffect *= adjustedDelayFactor
        if (bg > normalBgThreshold) {
            insulinEffect *= 1.2f
        }

        return insulinEffect
    }
    private fun predictFutureBg(
        bg: Float,
        iob: Float,
        variableSensitivity: Float,
        cob: Float,
        ci: Float,
        mealTime: Boolean,
        bfastTime: Boolean,
        lunchTime: Boolean,
        dinnerTime: Boolean,
        highcarbTime: Boolean,
        snackTime: Boolean,
        profile: OapsProfile
    ): Float {
        val (averageCarbAbsorptionTime, carbTypeFactor, estimatedCob) = when {
            highcarbTime -> Triple(3.5f, 0.75f, 100f) // Repas riche en glucides
            snackTime -> Triple(1.5f, 1.25f, 15f) // Snack
            mealTime -> Triple(2.5f, 1.0f, 55f) // Repas normal
            bfastTime -> Triple(3.5f, 1.0f, 55f) // breakfast
            lunchTime -> Triple(2.5f, 1.0f, 70f) // Repas normal
            dinnerTime -> Triple(2.5f, 1.0f, 70f) // Repas normal
            else -> Triple(2.5f, 1.0f, 70f) // Valeur par défaut si aucun type de repas spécifié
        }
        val absorptionTimeInMinutes = averageCarbAbsorptionTime * 60

        val insulinEffect = calculateInsulinEffect(
            bg, iob, variableSensitivity, cob, normalBgThreshold, recentSteps180Minutes,
            averageBeatsPerMinute.toFloat(), averageBeatsPerMinute10.toFloat(),profile.insulinDivisor.toFloat()
        )

        val carbEffect = if (absorptionTimeInMinutes != 0f && ci > 0f) {
            (estimatedCob / absorptionTimeInMinutes) * ci * carbTypeFactor
        } else {
            0f
        }

        var futureBg = bg - insulinEffect * 0.85f + carbEffect * 0.85f // Réduction de l'impact de l'insuline et des glucides
        if (futureBg < 50f) { // Ajustement du seuil minimum
            futureBg = 50f
        }

        return futureBg
    }


    private fun calculateSmoothBasalRate(
        tdd2Days: Float, // Total Daily Dose (TDD) pour le jour le plus récent
        tdd7Days: Float, // TDD pour le jour précédent
        currentBasalRate: Float // Le taux de basal actuel
    ): Float {
        // Poids pour le lissage. Plus la valeur est proche de 1, plus l'influence du jour le plus récent est grande.
        val weightRecent = 0.7f
        val weightPrevious = 1.0f - weightRecent

        // Calculer la TDD moyenne pondérée
        val weightedTdd = (tdd2Days * weightRecent) + (tdd7Days * weightPrevious)

        // Ajuster la basale en fonction de la TDD moyenne pondérée
        // Cette formule peut être ajustée en fonction de la logique souhaitée
        val adjustedBasalRate = currentBasalRate * (weightedTdd / tdd2Days)

        // Retourner la nouvelle basale lissée
        return adjustedBasalRate
    }
    private fun determineNoteBasedOnBg(bg: Double): String {
        return when {
            bg > 170 -> "more aggressive"
            bg in 90.0..100.0 -> "less aggressive"
            bg in 80.0..89.9 -> "too aggressive" // Vous pouvez ajuster ces valeurs selon votre logique
            bg < 80 -> "low treatment"
            else -> "normal" // Vous pouvez définir un autre message par défaut pour les cas non couverts
        }
    }
    private fun processNotesAndCleanUp(notes: String): String {
        return notes.lowercase()
            .replace(",", " ")
            .replace(".", " ")
            .replace("!", " ")
            //.replace("a", " ")
            .replace("an", " ")
            .replace("and", " ")
            .replace("\\s+", " ")
    }
    private fun parseNotes(startMinAgo: Int, endMinAgo: Int): String {
        val olderTimeStamp = now - endMinAgo * 60 * 1000
        val moreRecentTimeStamp = now - startMinAgo * 60 * 1000
        var notes = ""
        val recentNotes2: MutableList<String> = mutableListOf()
        val autoNote = determineNoteBasedOnBg(bg)
        recentNotes2.add(autoNote)
        notes += autoNote  // Ajout de la note auto générée

        recentNotes?.forEach { note ->
            if(note.timestamp > olderTimeStamp && note.timestamp <= moreRecentTimeStamp) {
                val noteText = note.note.lowercase()
                if (noteText.contains("sleep") || noteText.contains("sport") || noteText.contains("snack") || noteText.contains("bfast") || noteText.contains("lunch") || noteText.contains("dinner") ||
                    noteText.contains("lowcarb") || noteText.contains("highcarb") || noteText.contains("meal") || noteText.contains("fasting") ||
                    noteText.contains("low treatment") || noteText.contains("less aggressive") ||
                    noteText.contains("more aggressive") || noteText.contains("too aggressive") ||
                    noteText.contains("normal")) {

                    notes += if (notes.isEmpty()) recentNotes2 else " "
                    notes += note.note
                    recentNotes2.add(note.note)
                }
            }
        }

        notes = processNotesAndCleanUp(notes)
        return notes
    }
    // private fun isMealAnticipated(
    //     bg: Float,
    //     delta: Float,
    //     shortAvgDelta: Float,
    //     longAvgDelta: Float,
    //     hourOfDay: Int,
    //     recentSteps10Minutes: Int,
    //     lastSmbMinutesAgo: Int,
    //     historicalMealTimes: List<Int> // Liste des heures typiques des repas
    // ): Boolean {
    //     // Constants
    //     val significantDelta = 10.0f // Définir une augmentation significative de la glycémie
    //     val lowActivityThreshold = 100 // Seuil d'activité physique faible
    //     val rapidIncreaseThreshold = 5.0f // Seuil de delta pour une augmentation rapide
    //     val baseMinTimeSinceLastSmb = 9
    //     val highCarbMinTimeSinceLastSmb = 4
    //     val bgThreshold = if (hourOfDay in 6..9 || hourOfDay in 11..14 || hourOfDay in 18..21) 100.0f else 120.0f
    //
    //     // Calculate minTimeSinceLastSmb based on meal type
    //     val minTimeSinceLastSmb = if (isHighCarbMeal(delta, shortAvgDelta, longAvgDelta)) highCarbMinTimeSinceLastSmb else baseMinTimeSinceLastSmb
    //
    //     // Check conditions
    //     val isRapidBgIncrease = delta > significantDelta && shortAvgDelta > rapidIncreaseThreshold
    //     val isNearTypicalMealTime = historicalMealTimes.any { kotlin.math.abs(hourOfDay - it) <= 1 }
    //     val isBgAboveThreshold = bg > bgThreshold
    //     val isTimeSinceLastSmbSufficient = lastSmbMinutesAgo > minTimeSinceLastSmb && delta > 8
    //     val isLowActivity = recentSteps10Minutes < lowActivityThreshold
    //     val isDeltaslowingdown = delta < 5 || shortAvgDelta <= 4 || longAvgDelta <= 3
    //     val FCL = preferences.get(BooleanKey.OApsAIMIMLFCL)
    //
    //     // Determine if a meal is anticipated
    //     return FCL && isLowActivity && isTimeSinceLastSmbSufficient && (isRapidBgIncrease && isBgAboveThreshold && !isDeltaslowingdown || isNearTypicalMealTime && isBgAboveThreshold && !isDeltaslowingdown)
    // }
    //
    // private fun anticipateMeal(glucoseStatus: GlucoseStatus,lastSmbMinutesAgo: Int, historicalMealTimes: List<Int>): Boolean {
    //     val hourOfDay = Calendar.getInstance()[Calendar.HOUR_OF_DAY]
    //     return isMealAnticipated(
    //         bg = glucoseStatus.glucose.toFloat(),
    //         delta = glucoseStatus.delta.toFloat(),
    //         shortAvgDelta = glucoseStatus.shortAvgDelta.toFloat(),
    //         longAvgDelta = glucoseStatus.longAvgDelta.toFloat(),
    //         hourOfDay = hourOfDay,
    //         recentSteps10Minutes = recentSteps10Minutes,
    //         historicalMealTimes = historicalMealTimes,
    //         lastSmbMinutesAgo = lastSmbMinutesAgo
    //     )
    // }
    // Helper function to determine if a meal is high-carb
    private fun isHighCarbMeal(delta: Float, shortAvgDelta: Float, longAvgDelta: Float): Boolean {
        return delta > 15 && shortAvgDelta > 12 && longAvgDelta > 10
    }

    fun determine_basal(
        glucose_status: GlucoseStatus, currenttemp: CurrentTemp, iob_data_array: Array<IobTotal>, profile: OapsProfile, autosens_data: AutosensResult, mealData: MealData,
        microBolusAllowed: Boolean, currentTime: Long, flatBGsDetected: Boolean, dynIsfMode: Boolean
    ): RT {
        consoleError.clear()
        consoleLog.clear()
        var rT = RT(
            algorithm = APSResult.Algorithm.AIMI,
            runningDynamicIsf = dynIsfMode,
            timestamp = currentTime,
            consoleLog = consoleLog,
            consoleError = consoleError
        )
        val historicalMealTimes = listOf(7, 8, 12, 13, 14, 19, 20, 21)
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        this.bg = glucose_status.glucose
        val getlastBolusSMB = persistenceLayer.getNewestBolusOfType(BS.Type.SMB)
        val lastBolusSMBTime = getlastBolusSMB?.timestamp ?: 0L
        val lastBolusSMBMinutes = lastBolusSMBTime / 60000
        this.lastBolusSMBUnit = getlastBolusSMB?.amount?.toFloat() ?: 0.0F
        val diff = abs(now - lastBolusSMBTime)
        this.lastsmbtime = (diff / (60 * 1000)).toInt()
        this.maxIob = preferences.get(DoubleKey.ApsSmbMaxIob)
        this.maxSMB = preferences.get(DoubleKey.OApsAIMIMaxSMB)
        this.maxSMBHB = preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB)
        this.maxSMB = if (bg > 150) maxSMBHB else maxSMB
        this.tir1DAYabove = tirCalculator.averageTIR(tirCalculator.calculate(1, 65.0, 180.0))?.abovePct()!!
        this.currentTIRLow = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0))?.belowPct()!!
        this.currentTIRRange = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0))?.inRangePct()!!
        this.currentTIRAbove = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0))?.abovePct()!!
        this.lastHourTIRLow = tirCalculator.averageTIR(tirCalculator.calculateHour(80.0,140.0))?.belowPct()!!
        val lastHourTIRAbove = tirCalculator.averageTIR(tirCalculator.calculateHour(72.0, 140.0))?.abovePct()
        this.lastHourTIRLow100 = tirCalculator.averageTIR(tirCalculator.calculateHour(100.0,140.0))?.belowPct()!!
        this.lastHourTIRabove170 = tirCalculator.averageTIR(tirCalculator.calculateHour(100.0,170.0))?.abovePct()!!
        this.lastHourTIRabove120 = tirCalculator.averageTIR(tirCalculator.calculateHour(100.0,120.0))?.abovePct()!!
        val tirbasal3IR = tirCalculator.averageTIR(tirCalculator.calculate(3, 65.0, 120.0))?.inRangePct()
        val tirbasal3B = tirCalculator.averageTIR(tirCalculator.calculate(3, 65.0, 120.0))?.belowPct()
        val tirbasal3A = tirCalculator.averageTIR(tirCalculator.calculate(3, 65.0, 120.0))?.abovePct()
        val tirbasalhAP = tirCalculator.averageTIR(tirCalculator.calculateHour(65.0, 100.0))?.abovePct()
        this.enablebasal = preferences.get(BooleanKey.OApsAIMIEnableBasal)
        //this.now = System.currentTimeMillis()
        val calendarInstance = Calendar.getInstance()
        this.hourOfDay = calendarInstance[Calendar.HOUR_OF_DAY]
        val dayOfWeek = calendarInstance[Calendar.DAY_OF_WEEK]
        this.weekend = if (dayOfWeek == Calendar.SUNDAY || dayOfWeek == Calendar.SATURDAY) 1 else 0
        var lastCarbTimestamp = mealData.lastCarbTime
        if (lastCarbTimestamp.toInt() == 0) {
            val oneDayAgoIfNotFound = now - 24 * 60 * 60 * 1000
            lastCarbTimestamp = persistenceLayer.getMostRecentCarbByDate() ?: oneDayAgoIfNotFound
        }
        this.lastCarbAgeMin = ((now - lastCarbTimestamp) / (60 * 1000)).toInt()

        this.futureCarbs = persistenceLayer.getFutureCob().toFloat()
        if (lastCarbAgeMin < 15 && cob == 0.0f) {
            this.cob = persistenceLayer.getMostRecentCarbAmount()?.toFloat() ?: 0.0f
        }

        val fourHoursAgo = now - 4 * 60 * 60 * 1000
        this.recentNotes = persistenceLayer.getUserEntryDataFromTime(fourHoursAgo).blockingGet()

        this.tags0to60minAgo = parseNotes(0, 60)
        this.tags60to120minAgo = parseNotes(60, 120)
        this.tags120to180minAgo = parseNotes(120, 180)
        this.tags180to240minAgo = parseNotes(180, 240)
        this.delta = when (profileFunction.getUnits()) {
            GlucoseUnit.MMOL -> glucose_status.delta.toFloat() * 18
            else -> glucose_status.delta.toFloat()
        }
        this.shortAvgDelta = glucose_status.shortAvgDelta.toFloat()
        this.longAvgDelta = glucose_status.longAvgDelta.toFloat()
        val therapy = Therapy(persistenceLayer).also {
            it.updateStatesBasedOnTherapyEvents()
        }
        this.sleepTime = therapy.sleepTime
        this.snackTime = therapy.snackTime
        this.sportTime = therapy.sportTime
        this.lowCarbTime = therapy.lowCarbTime
        this.highCarbTime = therapy.highCarbTime
        this.mealTime = therapy.mealTime
        this.bfastTime = therapy.bfastTime
        this.lunchTime = therapy.lunchTime
        this.dinnerTime = therapy.dinnerTime
        this.fastingTime = therapy.fastingTime
        this.stopTime = therapy.stopTime
        this.mealruntime = therapy.getTimeElapsedSinceLastEvent("meal")
        this.bfastruntime = therapy.getTimeElapsedSinceLastEvent("bfast")
        this.lunchruntime = therapy.getTimeElapsedSinceLastEvent("lunch")
        this.dinnerruntime = therapy.getTimeElapsedSinceLastEvent("dinner")
        this.highCarbrunTime = therapy.getTimeElapsedSinceLastEvent("highcarb")
        this.snackrunTime = therapy.getTimeElapsedSinceLastEvent("snack")
        this.iscalibration = therapy.calibartionTime
        this.acceleratingUp = if (delta > 2 && delta - longAvgDelta > 2) 1 else 0
        this.decceleratingUp = if (delta > 0 && (delta < shortAvgDelta || delta < longAvgDelta)) 1 else 0
        this.acceleratingDown = if (delta < -2 && delta - longAvgDelta < -2) 1 else 0
        this.decceleratingDown = if (delta < 0 && (delta > shortAvgDelta || delta > longAvgDelta)) 1 else 0
        this.stable = if (delta>-3 && delta<3 && shortAvgDelta>-3 && shortAvgDelta<3 && longAvgDelta>-3 && longAvgDelta<3 && bg < 180) 1 else 0
        //val isMealAnticipated = anticipateMeal(glucose_status,lastsmbtime, historicalMealTimes)
         if (isMealModeCondition()){
             val pbolusM: Double = preferences.get(DoubleKey.OApsAIMIMealPrebolus)
                 rT.units = pbolusM
                 rT.reason.append("Microbolusing Meal Mode ${pbolusM}U. ")
             return rT
         }
        // if (((isMealAnticipated && iob in 0.5..3.0 && delta > 15 && shortAvgDelta > 10 && acceleratingUp == 1) || profile.temptargetSet && targetBg == 80.0f) && (hourOfDay in 8..10 || hourOfDay in 11..14 || hourOfDay in 18..21)){
        //     val pbolusM: Double = preferences.get(DoubleKey.OApsAIMIMealPrebolus)
        //     this.maxSMB = pbolusM
        //     rT.units = if (lastBolusSMBUnit != pbolusM.toFloat()) pbolusM else 0.0
        //     rT.reason.append("Microbolusing FCL Mode using Meal mode prebolus ${pbolusM}U. ")
        //     return rT
        // }
        if (isbfastModeCondition()){
            val pbolusbfast: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus)
            rT.units = pbolusbfast
            rT.reason.append("Microbolusing 1/2 Breakfast Mode ${pbolusbfast}U. ")
            return rT
        }
        if (isbfast2ModeCondition()){
            val pbolusbfast2: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus2)
            this.maxSMB = pbolusbfast2
            rT.units = pbolusbfast2
            rT.reason.append("Microbolusing 2/2 Breakfast Mode ${pbolusbfast2}U. ")
            return rT
        }
        if (isLunchModeCondition()){
            val pbolusLunch: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus)
                rT.units = pbolusLunch
                rT.reason.append("Microbolusing 1/2 Meal Mode ${pbolusLunch}U. ")
            return rT
        }
        if (isLunch2ModeCondition()){
            val pbolusLunch2: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus2)
            this.maxSMB = pbolusLunch2
            rT.units = pbolusLunch2
            rT.reason.append("Microbolusing 2/2 Meal Mode ${pbolusLunch2}U. ")
            return rT
        }
        if (isDinnerModeCondition()){
            val pbolusDinner: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus)
            rT.units = pbolusDinner
            rT.reason.append("Microbolusing 1/2 Meal Mode ${pbolusDinner}U. ")
            return rT
        }
        if (isDinner2ModeCondition()){
            val pbolusDinner2: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus2)
            this.maxSMB = pbolusDinner2
            rT.units = pbolusDinner2
            rT.reason.append("Microbolusing 2/2 Meal Mode ${pbolusDinner2}U. ")
            return rT
        }
        if (isHighCarbModeCondition()){
            val pbolusHC: Double = preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus)
            rT.units = pbolusHC
            rT.reason.append("Microbolusing High Carb Mode ${pbolusHC}U. ")
            return rT
        }
        if (issnackModeCondition()){
            val pbolussnack: Double = preferences.get(DoubleKey.OApsAIMISnackPrebolus)
            rT.units = pbolussnack
            rT.reason.append("Microbolusing High Carb Mode ${pbolussnack}U. ")
            return rT
        }

        var nowMinutes = calendarInstance[Calendar.HOUR_OF_DAY] + calendarInstance[Calendar.MINUTE] / 60.0 + calendarInstance[Calendar.SECOND] / 3600.0
        nowMinutes = (kotlin.math.round(nowMinutes * 100) / 100)  // Arrondi à 2 décimales
        val circadianSensitivity = (0.00000379 * nowMinutes.pow(5)) -
            (0.00016422 * nowMinutes.pow(4)) +
            (0.00128081 * nowMinutes.pow(3)) +
            (0.02533782 * nowMinutes.pow(2)) -
            (0.33275556 * nowMinutes) +
            1.38581503

        val circadianSmb = kotlin.math.round(
            ((0.00000379 * delta * nowMinutes.pow(5)) -
                (0.00016422 * delta * nowMinutes.pow(4)) +
                (0.00128081 * delta * nowMinutes.pow(3)) +
                (0.02533782 * delta * nowMinutes.pow(2)) -
                (0.33275556 * delta * nowMinutes) +
                1.38581503) * 100
        ) / 100  // Arrondi à 2 décimales
        // TODO eliminate
        val deliverAt = currentTime

        // TODO eliminate
        val profile_current_basal = roundBasal(profile.current_basal)
        var basal: Double

        // TODO eliminate
        val systemTime = currentTime

        // TODO eliminate
        val bgTime = glucose_status.date
        val minAgo = round((systemTime - bgTime) / 60.0 / 1000.0, 1)
        // TODO eliminate
        //bg = glucose_status.glucose.toFloat()
        //this.bg = bg.toFloat()
        // TODO eliminate
        val noise = glucose_status.noise
        // 38 is an xDrip error state that usually indicates sensor failure
        // all other BG values between 11 and 37 mg/dL reflect non-error-code BG values, so we should zero temp for those
        if (bg <= 10 || bg == 38.0 || noise >= 3) {  //Dexcom is in ??? mode or calibrating, or xDrip reports high noise
            rT.reason.append("CGM is calibrating, in ??? state, or noise is high")
        }
        if (minAgo > 12 || minAgo < -5) { // Dexcom data is too old, or way in the future
            rT.reason.append("If current system time $systemTime is correct, then BG data is too old. The last BG data was read ${minAgo}m ago at $bgTime")
            // if BG is too old/noisy, or is changing less than 1 mg/dL/5m for 45m, cancel any high temps and shorten any long zero temps
        } else if (bg > 60 && flatBGsDetected) {
            rT.reason.append("Error: CGM data is unchanged for the past ~45m")
        }

        // TODO eliminate
        val max_iob = profile.max_iob // maximum amount of non-bolus IOB OpenAPS will ever deliver
        this.maxIob = max_iob
        // if min and max are set, then set target to their average
        var target_bg = (profile.min_bg + profile.max_bg) / 2
        var min_bg = profile.min_bg
        var max_bg = profile.max_bg

        var sensitivityRatio: Double
        val high_temptarget_raises_sensitivity = profile.exercise_mode || profile.high_temptarget_raises_sensitivity
        val normalTarget = 130 // evaluate high/low temptarget against 100, not scheduled target (which might change)
        // when temptarget is 160 mg/dL, run 50% basal (120 = 75%; 140 = 60%),  80 mg/dL with low_temptarget_lowers_sensitivity would give 1.5x basal, but is limited to autosens_max (1.2x by default)
        val halfBasalTarget = profile.half_basal_exercise_target

        when {
            !profile.temptargetSet && recentSteps5Minutes >= 0 && (recentSteps30Minutes >= 500 || recentSteps180Minutes > 1500) && recentSteps10Minutes > 0 -> {
                this.targetBg = 130.0f
            }
            !profile.temptargetSet && eventualBG >= 160 && delta > 5 -> {
                var hyperTarget = max(110.0, profile.target_bg - (bg - profile.target_bg) / 3).toInt()
                hyperTarget = (hyperTarget * min(circadianSensitivity, 1.0)).toInt()
                hyperTarget = max(hyperTarget, 110)
                this.targetBg = hyperTarget.toFloat()
                target_bg = hyperTarget.toDouble()
                val c = (halfBasalTarget - normalTarget).toDouble()
                sensitivityRatio = c / (c + target_bg - normalTarget)
                // limit sensitivityRatio to profile.autosens_max (1.2x by default)
                sensitivityRatio = min(sensitivityRatio, profile.autosens_max)
                sensitivityRatio = round(sensitivityRatio, 2)
                consoleLog.add("Sensitivity ratio set to $sensitivityRatio based on temp target of $target_bg; ")
            }
            !profile.temptargetSet && circadianSmb > 0.1 && eventualBG < 100 -> {
                val hypoTarget = 130 * max(1.0, circadianSensitivity)
                this.targetBg = min(hypoTarget.toFloat(), 166.0f)
                target_bg = targetBg.toDouble()
                val c = (halfBasalTarget - normalTarget).toDouble()
                sensitivityRatio = c / (c + target_bg - normalTarget)
                // limit sensitivityRatio to profile.autosens_max (1.2x by default)
                sensitivityRatio = min(sensitivityRatio, profile.autosens_max)
                sensitivityRatio = round(sensitivityRatio, 2)
                consoleLog.add("Sensitivity ratio set to $sensitivityRatio based on temp target of $target_bg; ")
            }
            else -> {
                val defaultTarget = profile.target_bg
                this.targetBg = defaultTarget.toFloat()
                target_bg = targetBg.toDouble()
            }
        }
        if (high_temptarget_raises_sensitivity && profile.temptargetSet && target_bg > normalTarget
            || profile.low_temptarget_lowers_sensitivity && profile.temptargetSet && target_bg < normalTarget
        ) {
            // w/ target 100, temp target 110 = .89, 120 = 0.8, 140 = 0.67, 160 = .57, and 200 = .44
            // e.g.: Sensitivity ratio set to 0.8 based on temp target of 120; Adjusting basal from 1.65 to 1.35; ISF from 58.9 to 73.6
            //sensitivityRatio = 2/(2+(target_bg-normalTarget)/40);
            val c = (halfBasalTarget - normalTarget).toDouble()
            sensitivityRatio = c / (c + target_bg - normalTarget)
            // limit sensitivityRatio to profile.autosens_max (1.2x by default)
            sensitivityRatio = min(sensitivityRatio, profile.autosens_max)
            sensitivityRatio = round(sensitivityRatio, 2)
            consoleLog.add("Sensitivity ratio set to $sensitivityRatio based on temp target of $target_bg; ")
        } else {
            sensitivityRatio = autosens_data.ratio
            consoleLog.add("Autosens ratio: $sensitivityRatio; ")
        }
        basal = profile.current_basal * sensitivityRatio
        basal = roundBasal(basal)
        if (basal != profile_current_basal)
            consoleLog.add("Adjusting basal from $profile_current_basal to $basal; ")
        else
            consoleLog.add("Basal unchanged: $basal; ")

        // adjust min, max, and target BG for sensitivity, such that 50% increase in ISF raises target from 100 to 120
        if (profile.temptargetSet) {
            consoleLog.add("Temp Target set, not adjusting with autosens")
        } else {
            if (profile.sensitivity_raises_target && autosens_data.ratio < 1 || profile.resistance_lowers_target && autosens_data.ratio > 1) {
                // with a target of 100, default 0.7-1.2 autosens min/max range would allow a 93-117 target range
                min_bg = round((min_bg - 60) / autosens_data.ratio, 0) + 60
                max_bg = round((max_bg - 60) / autosens_data.ratio, 0) + 60
                var new_target_bg = round((target_bg - 60) / autosens_data.ratio, 0) + 60
                // don't allow target_bg below 80
                new_target_bg = max(80.0, new_target_bg)
                if (target_bg == new_target_bg)
                    consoleLog.add("target_bg unchanged: $new_target_bg; ")
                else
                    consoleLog.add("target_bg from $target_bg to $new_target_bg; ")

                target_bg = new_target_bg
            }
        }

        val iobArray = iob_data_array
        val iob_data = iobArray[0]
        this.iob = iob_data.iob.toFloat()

        val tick: String = if (glucose_status.delta > -0.5) {
            "+" + round(glucose_status.delta)
        } else {
            round(glucose_status.delta).toString()
        }
        val minDelta = min(glucose_status.delta, glucose_status.shortAvgDelta)
        val minAvgDelta = min(glucose_status.shortAvgDelta, glucose_status.longAvgDelta)
        // val maxDelta = max(glucose_status.delta, max(glucose_status.shortAvgDelta, glucose_status.longAvgDelta))
        val tdd7P: Double = preferences.get(DoubleKey.OApsAIMITDD7)
        var tdd7Days = profile.TDD
        if (tdd7Days == 0.0 || tdd7Days < tdd7P) tdd7Days = tdd7P
        this.tdd7DaysPerHour = (tdd7Days / 24).toFloat()

        var tdd2Days = tddCalculator.averageTDD(tddCalculator.calculate(2, allowMissingDays = false))?.data?.totalAmount?.toFloat() ?: 0.0f
        if (tdd2Days == 0.0f || tdd2Days < tdd7P) tdd2Days = tdd7P.toFloat()
        this.tdd2DaysPerHour = tdd2Days / 24

        var tddDaily = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount?.toFloat() ?: 0.0f
        if (tddDaily == 0.0f || tddDaily < tdd7P / 2) tddDaily = tdd7P.toFloat()
        this.tddPerHour = tddDaily / 24

        var tdd24Hrs = tddCalculator.calculateDaily(-24, 0)?.totalAmount?.toFloat() ?: 0.0f
        if (tdd24Hrs == 0.0f) tdd24Hrs = tdd7P.toFloat()

        this.tdd24HrsPerHour = tdd24Hrs / 24
        var sens = profile.variable_sens
        this.variableSensitivity = sens.toFloat()
        consoleError.add("CR:${profile.carb_ratio}")
        this.predictedBg = predictFutureBg(bg.toFloat(), iob, variableSensitivity, cob, ci,mealTime,bfastTime,lunchTime,dinnerTime,highCarbTime,snackTime,profile)
        val insulinEffect = calculateInsulinEffect(bg.toFloat(),iob,variableSensitivity,cob,normalBgThreshold,recentSteps180Minutes,averageBeatsPerMinute.toFloat(),averageBeatsPerMinute10.toFloat(),profile.insulinDivisor.toFloat())

        val now = System.currentTimeMillis()
        val timeMillis5 = now - 5 * 60 * 1000 // 5 minutes en millisecondes
        val timeMillis10 = now - 10 * 60 * 1000 // 10 minutes en millisecondes
        val timeMillis15 = now - 15 * 60 * 1000 // 15 minutes en millisecondes
        val timeMillis30 = now - 30 * 60 * 1000 // 30 minutes en millisecondes
        val timeMillis60 = now - 60 * 60 * 1000 // 60 minutes en millisecondes
        val timeMillis180 = now - 180 * 60 * 1000 // 180 minutes en millisecondes

        val allStepsCounts = persistenceLayer.getStepsCountFromTimeToTime(timeMillis180, now)

        if (preferences.get(BooleanKey.OApsAIMIEnableStepsFromWatch)) {
        allStepsCounts.forEach { stepCount ->
            val timestamp = stepCount.timestamp
            if (timestamp >= timeMillis5) {
                this.recentSteps5Minutes = stepCount.steps5min
            }
            if (timestamp >= timeMillis10) {
                this.recentSteps10Minutes = stepCount.steps10min
            }
            if (timestamp >= timeMillis15) {
                this.recentSteps15Minutes = stepCount.steps15min
            }
            if (timestamp >= timeMillis30) {
                this.recentSteps30Minutes = stepCount.steps30min
            }
            if (timestamp >= timeMillis60) {
                this.recentSteps60Minutes = stepCount.steps60min
            }
            if (timestamp >= timeMillis180) {
                this.recentSteps180Minutes = stepCount.steps180min
            }
        }
        }else{
            this.recentSteps5Minutes = StepService.getRecentStepCount5Min()
            this.recentSteps10Minutes = StepService.getRecentStepCount10Min()
            this.recentSteps15Minutes = StepService.getRecentStepCount15Min()
            this.recentSteps30Minutes = StepService.getRecentStepCount30Min()
            this.recentSteps60Minutes = StepService.getRecentStepCount60Min()
            this.recentSteps180Minutes = StepService.getRecentStepCount180Min()
        }

        try {
            val heartRates5 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis5,now)
            this.averageBeatsPerMinute = heartRates5.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute = 80.0
        }
        try {
            val heartRates10 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis10,now)
            this.averageBeatsPerMinute10 = heartRates10.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute10 = 80.0
        }
        try {
            val heartRates60 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis60,now)
            this.averageBeatsPerMinute60 = heartRates60.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute60 = 80.0
        }
        try {

            val heartRates180 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis180,now)
            this.averageBeatsPerMinute180 = heartRates180.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute180 = 80.0
        }
        if (tdd7Days.toFloat() != 0.0f) {
            basalaimi = (tdd7Days / preferences.get(DoubleKey.OApsAIMIweight)).toFloat()
        }
        this.basalaimi = calculateSmoothBasalRate(tdd7P.toFloat(),tdd7Days.toFloat(),basalaimi)
        if (tdd7Days.toFloat() != 0.0f) {
            this.ci = (450 / tdd7Days).toFloat()
        }

        val choKey: Double = preferences.get(DoubleKey.OApsAIMICHO)
        if (ci != 0.0f && ci != Float.POSITIVE_INFINITY && ci != Float.NEGATIVE_INFINITY) {
            this.aimilimit = (choKey / ci).toFloat()
        } else {
            this.aimilimit = (choKey / profile.carb_ratio).toFloat()
        }
        val timenow = LocalTime.now().hour
        val sixAMHour = LocalTime.of(6, 0).hour
        // if (averageBeatsPerMinute != 0.0) {
        //     this.basalaimi = when {
        //         averageBeatsPerMinute >= averageBeatsPerMinute180 && recentSteps5Minutes > 100 && recentSteps10Minutes > 200 -> (basalaimi * 0.65).toFloat()
        //         averageBeatsPerMinute180 != 80.0 && averageBeatsPerMinute > averageBeatsPerMinute180 && bg >= 130 && recentSteps10Minutes == 0 && timenow > sixAMHour -> (basalaimi * 1.2).toFloat()
        //         averageBeatsPerMinute180 != 80.0 && averageBeatsPerMinute < averageBeatsPerMinute180 && recentSteps10Minutes == 0 && bg >= 110 -> (basalaimi * 1.1).toFloat()
        //         else -> basalaimi
        //     }
        // }

        val pregnancyEnable = preferences.get(BooleanKey.OApsAIMIpregnancy)

        if (tirbasal3B != null && pregnancyEnable && tirbasal3IR != null) {
            this.basalaimi = when {
                tirbasalhAP != null && tirbasalhAP >= 5 -> (basalaimi * 2.0).toFloat()
                lastHourTIRAbove != null && lastHourTIRAbove >= 2 -> (basalaimi * 1.8).toFloat()
                timenow < sixAMHour -> (basalaimi * 1.4).toFloat()
                timenow > sixAMHour -> (basalaimi * 1.6).toFloat()
                tirbasal3B <= 5 && tirbasal3IR in 70.0..80.0 -> (basalaimi * 1.1).toFloat()
                tirbasal3B <= 5 && tirbasal3IR <= 70 -> (basalaimi * 1.3).toFloat()
                tirbasal3B > 5 && tirbasal3A!! < 5 -> (basalaimi * 0.85).toFloat()
                else -> basalaimi  // Default case to handle any condition not explicitly matched
            }
        }

        this.basalaimi = if (basalaimi > profile_current_basal * 2) (profile_current_basal.toFloat() * 2) else basalaimi

        this.variableSensitivity = if (bg < 130) {
            profile.sens.toFloat()
        } else {
            max(
                profile.sens.toFloat() / 2.5f,
                sens.toFloat() * calculateGFactor(delta, lastHourTIRabove120, bg.toFloat()).toFloat()
            )
        }

        if (recentSteps5Minutes > 100 && recentSteps10Minutes > 200 && bg < 130 && delta < 10 || recentSteps180Minutes > 1500 && bg < 130 && delta < 10) {
            this.variableSensitivity *= 1.5f * calculateGFactor(delta, lastHourTIRabove120, bg.toFloat()).toFloat()
        }
        if (recentSteps30Minutes > 500 && recentSteps5Minutes >= 0 && recentSteps5Minutes < 100 && bg < 130 && delta < 10) {
            this.variableSensitivity *= 1.3f * calculateGFactor(delta, lastHourTIRabove120, bg.toFloat()).toFloat()
        }
        if (variableSensitivity < 20) this.variableSensitivity = profile.sens.toFloat()
        if (variableSensitivity > (3 * profile.sens.toFloat())) this.variableSensitivity = profile.sens.toFloat() * 3

        sens = variableSensitivity.toDouble()
        //calculate BG impact: the amount BG "should" be rising or falling based on insulin activity alone
        val bgi = round((-iob_data.activity * sens * 5), 2)
        // project deviations for 30 minutes
        var deviation = round(30 / 5 * (minDelta - bgi))
        // don't overreact to a big negative delta: use minAvgDelta if deviation is negative
        if (deviation < 0) {
            deviation = round((30 / 5) * (minAvgDelta - bgi))
            // and if deviation is still negative, use long_avgdelta
            if (deviation < 0) {
                deviation = round((30 / 5) * (glucose_status.longAvgDelta - bgi))
            }
        }
        // calculate the naive (bolus calculator math) eventual BG based on net IOB and sensitivity
        val naive_eventualBG = round(bg - (iob_data.iob * sens), 0)
        // and adjust it for the deviation above
        this.eventualBG = naive_eventualBG + deviation

        // raise target for noisy / raw CGM data
        if (bg > max_bg && profile.adv_target_adjustments && !profile.temptargetSet) {
            // with target=100, as BG rises from 100 to 160, adjustedTarget drops from 100 to 80
            val adjustedMinBG = round(max(80.0, min_bg - (bg - min_bg) / 3.0), 0)
            val adjustedTargetBG = round(max(80.0, target_bg - (bg - target_bg) / 3.0), 0)
            val adjustedMaxBG = round(max(80.0, max_bg - (bg - max_bg) / 3.0), 0)
            // if eventualBG, naive_eventualBG, and target_bg aren't all above adjustedMinBG, don’t use it
            //console.error("naive_eventualBG:",naive_eventualBG+", eventualBG:",eventualBG);
            if (eventualBG > adjustedMinBG && naive_eventualBG > adjustedMinBG && min_bg > adjustedMinBG) {
                consoleLog.add("Adjusting targets for high BG: min_bg from $min_bg to $adjustedMinBG; ")
                min_bg = adjustedMinBG
            } else {
                consoleLog.add("min_bg unchanged: $min_bg; ")
            }
            // if eventualBG, naive_eventualBG, and target_bg aren't all above adjustedTargetBG, don’t use it
            if (eventualBG > adjustedTargetBG && naive_eventualBG > adjustedTargetBG && target_bg > adjustedTargetBG) {
                consoleLog.add("target_bg from $target_bg to $adjustedTargetBG; ")
                target_bg = adjustedTargetBG
            } else {
                consoleLog.add("target_bg unchanged: $target_bg; ")
            }
            // if eventualBG, naive_eventualBG, and max_bg aren't all above adjustedMaxBG, don’t use it
            if (eventualBG > adjustedMaxBG && naive_eventualBG > adjustedMaxBG && max_bg > adjustedMaxBG) {
                consoleError.add("max_bg from $max_bg to $adjustedMaxBG")
                max_bg = adjustedMaxBG
            } else {
                consoleError.add("max_bg unchanged: $max_bg")
            }
        }

        //val expectedDelta = calculateExpectedDelta(target_bg, eventualBG, bgi)
        val modelcal = calculateSMBFromModel()
        // min_bg of 90 -> threshold of 65, 100 -> 70 110 -> 75, and 130 -> 85
        var threshold = min_bg - 0.5 * (min_bg - 40)
        if (profile.lgsThreshold != null) {
            val lgsThreshold = profile.lgsThreshold ?: error("lgsThreshold missing")
            if (lgsThreshold > threshold) {
                consoleError.add("Threshold set from ${convertBG(threshold)} to ${convertBG(lgsThreshold.toDouble())}; ")
                threshold = lgsThreshold.toDouble()
            }
        }
        this.predictedSMB = modelcal
        if (preferences.get(BooleanKey.OApsAIMIMLtraining) && csvfile.exists()){
            val allLines = csvfile.readLines()
            val minutesToConsider = 2500.0
            val linesToConsider = (minutesToConsider / 5).toInt()
            if (allLines.size > linesToConsider) {
                val refinedSMB = neuralnetwork5(delta, shortAvgDelta, longAvgDelta, predictedSMB, profile)
                this.predictedSMB = refinedSMB
                basal =
                    when {
                        (honeymoon && bg < 170) -> basalaimi * 0.65
                        else -> basalaimi.toDouble()
                    }
                basal = roundBasal(basal)
            }
            rT.reason.append("csvfile ${csvfile.exists()}")
        }else {
            rT.reason.append("ML Decision data training","ML decision has no enough data to refine the decision")
        }

        var smbToGive = if (bg > 160  && delta > 8 && predictedSMB == 0.0f) modelcal else predictedSMB
        smbToGive = if (honeymoon && bg < 170) smbToGive * 0.8f else smbToGive

        val morningfactor: Double = preferences.get(DoubleKey.OApsAIMIMorningFactor) / 100.0
        val afternoonfactor: Double = preferences.get(DoubleKey.OApsAIMIAfternoonFactor) / 100.0
        val eveningfactor: Double = preferences.get(DoubleKey.OApsAIMIEveningFactor) / 100.0
        val hyperfactor: Double = preferences.get(DoubleKey.OApsAIMIHyperFactor) / 100.0
        val highcarbfactor: Double = preferences.get(DoubleKey.OApsAIMIHCFactor) / 100.0
        val mealfactor: Double = preferences.get(DoubleKey.OApsAIMIMealFactor) / 100.0
        val bfastfactor: Double = preferences.get(DoubleKey.OApsAIMIBFFactor) / 100.0
        val lunchfactor: Double = preferences.get(DoubleKey.OApsAIMILunchFactor) / 100.0
        val dinnerfactor: Double = preferences.get(DoubleKey.OApsAIMIDinnerFactor) / 100.0
        val snackfactor: Double = preferences.get(DoubleKey.OApsAIMISnackFactor) / 100.0
        val sleepfactor: Double = preferences.get(DoubleKey.OApsAIMIsleepFactor) / 100.0
        //val fclfactor: Double = preferences.get(DoubleKey.OApsAIMIFCLFactor) / 100.0

        val adjustedFactors = adjustFactorsBasedOnBgAndHypo(
                morningfactor.toFloat(), afternoonfactor.toFloat(), eveningfactor.toFloat()
            )

        val (adjustedMorningFactor, adjustedAfternoonFactor, adjustedEveningFactor) = adjustedFactors

        // Appliquer les ajustements en fonction de l'heure de la journée
        smbToGive = when {
            bg > 160 && delta > 4 && iob < 0.7 && honeymoon && smbToGive == 0.0f && LocalTime.now().run { (hour in 23..23 || hour in 0..6) } -> 0.15f
            bg > 120 && delta > 8 && iob < 1.0 && !honeymoon && smbToGive < 0.1f                                                             -> profile_current_basal.toFloat()
            highCarbTime                                                                                                                     -> smbToGive * highcarbfactor.toFloat()
            mealTime                                                                                                                         -> smbToGive * mealfactor.toFloat()
            bfastTime                                                                                                                        -> smbToGive * bfastfactor.toFloat()
            lunchTime                                                                                                                        -> smbToGive * lunchfactor.toFloat()
            dinnerTime                                                                                                                       -> smbToGive * dinnerfactor.toFloat()
            snackTime                                                                                                                        -> smbToGive * snackfactor.toFloat()
            sleepTime                                                                                                                        -> smbToGive * sleepfactor.toFloat()
            //isMealAnticipated                                                                                                              -> smbToGive * fclfactor.toFloat()
            hourOfDay in 1..11                                                                                                         -> smbToGive * adjustedMorningFactor.toFloat()
            hourOfDay in 12..18                                                                                                        -> smbToGive * adjustedAfternoonFactor.toFloat()
            hourOfDay in 19..23                                                                                                        -> smbToGive * adjustedEveningFactor.toFloat()
            //bg > 140                                                                                                                         -> smbToGive * hyperfactor.toFloat()
            else -> smbToGive
        }
        rT.reason.append("adjustedMorningFactor $adjustedMorningFactor")
        rT.reason.append("adjustedAfternoonFactor $adjustedAfternoonFactor")
        rT.reason.append("adjustedEveningFactor $adjustedEveningFactor")


        smbToGive = applySafetyPrecautions(glucose_status,smbToGive)
        smbToGive = roundToPoint05(smbToGive)

        logDataMLToCsv(predictedSMB, smbToGive)
        logDataToCsv(predictedSMB, smbToGive)
        logDataToCsvHB(predictedSMB, smbToGive)

        rT = RT(
            algorithm = APSResult.Algorithm.AIMI,
            runningDynamicIsf = dynIsfMode,
            timestamp = currentTime,
            bg = bg,
            tick = tick,
            eventualBG = eventualBG,
            targetBG = target_bg,
            insulinReq = 0.0,
            deliverAt = deliverAt, // The time at which the microbolus should be delivered
            sensitivityRatio = sensitivityRatio, // autosens ratio (fraction of normal basal)
            consoleLog = consoleLog,
            consoleError = consoleError,
            variable_sens = variableSensitivity.toDouble()
        )
        var rate = when {
            snackTime && snackrunTime in 0..30 && delta < 15 -> calculateRate(basal, profile_current_basal, 4.0, "AI Force basal because snackTime $snackrunTime.", currenttemp, rT)
            mealTime && mealruntime in 0..30 && delta < 15 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because mealTime $mealruntime.", currenttemp, rT)
            bfastTime && bfastruntime in 0..30 && delta < 15 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because bfastTime $bfastruntime.", currenttemp, rT)
            lunchTime && lunchruntime in 0..30 && delta < 15 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because lunchTime $lunchruntime.", currenttemp, rT)
            dinnerTime && dinnerruntime in 0..30 && delta < 15 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because dinnerTime $dinnerruntime.", currenttemp, rT)
            highCarbTime && highCarbrunTime in 0..30 && delta < 15 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because highcarb $highcarbfactor.", currenttemp, rT)
            fastingTime -> calculateRate(profile_current_basal, profile_current_basal, delta.toDouble(), "AI Force basal because fastingTime", currenttemp, rT)
            sportTime && bg > 169 && delta > 4 -> calculateRate(profile_current_basal, profile_current_basal, delta.toDouble(), "AI Force basal because sportTime && bg > 170", currenttemp, rT)
            //!honeymoon && delta in 0.0 .. 7.0 && bg in 81.0..111.0 -> calculateRate(profile_current_basal, profile_current_basal, delta.toDouble(), "AI Force basal because bg lesser than 110 and delta lesser than 8", currenttemp, rT)
            honeymoon && delta in 0.0.. 6.0 && bg in 99.0..141.0 -> calculateRate(profile_current_basal, profile_current_basal, delta.toDouble(), "AI Force basal because honeymoon and bg lesser than 140 and delta lesser than 6", currenttemp, rT)
            bg in 81.0..99.0 && delta in 3.0..7.0 && honeymoon -> calculateRate(basal, profile_current_basal, 1.0, "AI Force basal because bg is between 80 and 100 with a small delta.", currenttemp, rT)
            //bg > 145 && delta > 0 && smbToGive == 0.0f && !honeymoon -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because bg is greater than 145 and SMB = 0U.", currenttemp, rT)
            bg > 130 && delta > 0 && smbToGive == 0.0f && honeymoon -> calculateRate(basal, profile_current_basal, 5.0, "AI Force basal because bg is greater than 120 and SMB = 0U.", currenttemp, rT)
            else -> null
        }
        rate?.let {
            rT.rate = it
            rT.deliverAt = deliverAt
            rT.duration = 30
            return rT
        }

        val enableSMB = enablesmb(profile, microBolusAllowed, mealData, target_bg)
        val csf = sens / profile.carb_ratio
        consoleError.add("profile.sens: ${profile.sens}, sens: $sens, CSF: $csf")

        rT.COB = mealData.mealCOB
        rT.IOB = iob_data.iob
        rT.reason.append(
            "COB: ${round(mealData.mealCOB, 1).withoutZeros()}, Dev: ${convertBG(deviation.toDouble())}, BGI: ${convertBG(bgi)}, ISF: ${convertBG(sens)}, CR: ${
                round(profile.carb_ratio, 2)
                    .withoutZeros()
            }, Target: ${convertBG(target_bg)}}"
        )

        val (conditionResult, conditionsTrue) = isCriticalSafetyCondition()
        val logTemplate = buildString {
            appendLine("The ai model predicted SMB of {predictedSMB}u and after safety requirements and rounding to .05, requested {smbToGive}u to the pump")
            appendLine("Version du plugin OpenApsAIMI-V3-DBA2-Cata, 01 August 2024")
            appendLine("adjustedFactors: {adjustedFactors}")
            appendLine()
            appendLine("modelcal: {modelcal}")
            appendLine("predictedSMB: {predictedSMB}")
            //appendLine("isMealAnticipated: {isMealAnticipated}")
            appendLine("Max IOB: {maxIob}")
            appendLine("Max SMB: {maxSMB}")
            appendLine("sleep: {sleepTime}")
            appendLine("sport: {sportTime}")
            appendLine("snack: {snackTime}")
            appendLine("lowcarb: {lowCarbTime}")
            appendLine("highcarb: {highCarbTime}")
            appendLine("meal: {mealTime}")
            appendLine("lunch: {bfastTime}")
            appendLine("lunch: {lunchTime}")
            appendLine("dinner: {dinnerTime}")
            appendLine("fastingtime: {fastingTime}")
            appendLine("intervalsmb: {intervalsmb}")
            appendLine("mealruntime: {mealruntime}")
            appendLine("bfastruntime: {bfastruntime}")
            appendLine("lunchruntime: {lunchruntime}")
            appendLine("dinnerruntime: {dinnerruntime}")
            appendLine("snackrunTime: {snackrunTime}")
            appendLine("highCarbrunTime: {highCarbrunTime}")
            appendLine()
            appendLine("insulinEffect: {insulinEffect}")
            appendLine("circadianSmb: {circadianSmb}")
            appendLine("circadianSensitivity: {circadianSensitivity}")
            appendLine("bg: {bg}")
            appendLine("targetBG: {targetBg}")
            appendLine("futureBg: {predictedBg}")
            appendLine("eventuelBG: {eventualBG}")
            appendLine()
            appendLine("delta: {delta}")
            appendLine("short avg delta: {shortAvgDelta}")
            appendLine("long avg delta: {longAvgDelta}")
            appendLine()
            appendLine("accelerating_up: {acceleratingUp}")
            appendLine("deccelerating_up: {decceleratingUp}")
            appendLine("accelerating_down: {acceleratingDown}")
            appendLine("deccelerating_down: {decceleratingDown}")
            appendLine("stable: {stable}")
            appendLine()
            appendLine("IOB: {iob}")
            appendLine("tdd 7d/h: {tdd7DaysPerHour}")
            appendLine("tdd 2d/h: {tdd2DaysPerHour}")
            appendLine("tdd daily/h: {tddPerHour}")
            appendLine("tdd 24h/h: {tdd24HrsPerHour}")
            appendLine()
            appendLine("enablebasal: {enablebasal}")
            appendLine("basalaimi: {basalaimi}")
            appendLine()
            appendLine("ISF: {variableSensitivity}")
            appendLine()
            appendLine("Hour of day: {hourOfDay}")
            appendLine("Weekend: {weekend}")
            appendLine("5 Min Steps: {recentSteps5Minutes}")
            appendLine("10 Min Steps: {recentSteps10Minutes}")
            appendLine("15 Min Steps: {recentSteps15Minutes}")
            appendLine("30 Min Steps: {recentSteps30Minutes}")
            appendLine("60 Min Steps: {recentSteps60Minutes}")
            appendLine("180 Min Steps: {recentSteps180Minutes}")
            appendLine()
            appendLine("Heart Beat(average past 5 minutes): {averageBeatsPerMinute}")
            appendLine("Heart Beat(average past 10 minutes): {averageBeatsPerMinute10}")
            appendLine("Heart Beat(average past 60 minutes): {averageBeatsPerMinute60}")
            appendLine("Heart Beat(average past 180 minutes): {averageBeatsPerMinute180}")
            appendLine()
            appendLine("COB: {cob}g Future: {futureCarbs}g")
            appendLine("COB Age Min: {lastCarbAgeMin}")
            appendLine()
            appendLine("tags0to60minAgo: {tags0to60minAgo}")
            appendLine("tags60to120minAgo: {tags60to120minAgo}")
            appendLine("tags180to240minAgo: {tags180to240minAgo}")
            appendLine()
            appendLine("currentTIRLow: {currentTIRLow}")
            appendLine("currentTIRRange: {currentTIRRange}")
            appendLine("currentTIRAbove: {currentTIRAbove}")
            appendLine("lastHourTIRLow: {lastHourTIRLow}")
            appendLine()
            appendLine("lastHourTIRLow100: {lastHourTIRLow100}")
            appendLine("lastHourTIRabove120: {lastHourTIRabove120}")
            appendLine("lastHourTIRabove170: {lastHourTIRabove170}")
            appendLine()
            appendLine("isCriticalSafetyCondition: {conditionResult}, True Conditions: {conditionsTrue}")
            appendLine()
            appendLine("lastBolusSMBMinutes: {lastBolusSMBMinutes}")
            appendLine()
            appendLine("lastsmbtime: {lastsmbtime}")
            appendLine()
            appendLine("lastCarbAgeMin: {lastCarbAgeMin}")
        }

        val valueMap = mapOf(
                "modelcal" to modelcal,
                "predictedSMB" to predictedSMB,
                "smbToGive" to smbToGive,
                //"isMealAnticipated" to isMealAnticipated,
                "adjustedFactors" to adjustedFactors,
                "maxIob" to maxIob,
                "maxSMB" to maxSMB,
                "sleepTime" to sleepTime,
                "sportTime" to sportTime,
                "snackTime" to snackTime,
                "lowCarbTime" to lowCarbTime,
                "highCarbTime" to highCarbTime,
                "mealTime" to mealTime,
                "bfastTime" to bfastTime,
                "lunchTime" to lunchTime,
                "dinnerTime" to dinnerTime,
                "fastingTime" to fastingTime,
                "intervalsmb" to intervalsmb,
                "mealruntime" to mealruntime,
                "bfastruntime" to bfastruntime,
                "lunchruntime" to lunchruntime,
                "dinnerruntime" to dinnerruntime,
                "snackrunTime" to snackrunTime,
                "highCarbrunTime" to highCarbrunTime,
                "insulinEffect" to insulinEffect,
                "circadianSmb" to circadianSmb,
                "circadianSensitivity" to circadianSensitivity,
                "bg" to bg,
                "targetBg" to targetBg,
                "predictedBg" to predictedBg,
                "eventualBG" to eventualBG,
                "delta" to delta,
                "shortAvgDelta" to shortAvgDelta,
                "longAvgDelta" to longAvgDelta,
                "acceleratingUp" to acceleratingUp,
                "decceleratingUp" to decceleratingUp,
                "acceleratingDown" to acceleratingDown,
                "decceleratingDown" to decceleratingDown,
                "stable" to stable,
                "iob" to iob,
                "tdd7DaysPerHour" to roundToPoint05(tdd7DaysPerHour),
                "tdd2DaysPerHour" to roundToPoint05(tdd2DaysPerHour),
                "tddPerHour" to roundToPoint05(tddPerHour),
                "tdd24HrsPerHour" to roundToPoint05(tdd24HrsPerHour),
                "enablebasal" to enablebasal,
                "basalaimi" to basalaimi,
                "variableSensitivity" to variableSensitivity,
                "hourOfDay" to hourOfDay,
                "weekend" to weekend,
                "recentSteps5Minutes" to recentSteps5Minutes,
                "recentSteps10Minutes" to recentSteps10Minutes,
                "recentSteps15Minutes" to recentSteps15Minutes,
                "recentSteps30Minutes" to recentSteps30Minutes,
                "recentSteps60Minutes" to recentSteps60Minutes,
                "recentSteps180Minutes" to recentSteps180Minutes,
                "averageBeatsPerMinute" to averageBeatsPerMinute,
                "averageBeatsPerMinute10" to averageBeatsPerMinute10,
                "averageBeatsPerMinute60" to averageBeatsPerMinute60,
                "averageBeatsPerMinute180" to averageBeatsPerMinute180,
                "cob" to cob,
                "futureCarbs" to futureCarbs,
                "lastCarbAgeMin" to lastCarbAgeMin,
                "tags0to60minAgo" to tags0to60minAgo,
                "tags60to120minAgo" to tags60to120minAgo,
                "tags120to180minAgo" to tags120to180minAgo,
                "tags180to240minAgo" to tags180to240minAgo,
                "currentTIRLow" to currentTIRLow,
                "currentTIRRange" to currentTIRRange,
                "currentTIRAbove" to currentTIRAbove,
                "lastHourTIRLow" to lastHourTIRLow,
                "lastHourTIRLow100" to lastHourTIRLow100,
                "lastHourTIRabove120" to lastHourTIRabove120,
                "lastHourTIRabove170" to lastHourTIRabove170,
                "conditionResult" to conditionResult,
                "conditionsTrue" to conditionsTrue,
                "lastBolusSMBMinutes" to lastBolusSMBMinutes,
                "lastsmbtime" to lastsmbtime,
                "lastCarbAgeMin" to lastCarbAgeMin
            )
        val filledLog = valueMap.entries.fold(logTemplate) { acc, entry ->
            acc.replace("{${entry.key}}", entry.value.toString())
        }
        rT.reason.append(filledLog)

        //rT.reason.append(logAIMI)
        // eventual BG is at/above target
        // if iob is over max, just cancel any temps
        if (eventualBG >= max_bg) {
            rT.reason.append("Eventual BG " + convertBG(eventualBG) + " >= " + convertBG(max_bg) + ", ")
        }
        if (iob_data.iob > max_iob) {
            rT.reason.append("IOB ${round(iob_data.iob, 2)} > max_iob $max_iob")
            return if (currenttemp.duration > 15 && (roundBasal(basal) == roundBasal(currenttemp.rate))) {
                rT.reason.append(", temp ${currenttemp.rate} ~ req ${round(basal, 2).withoutZeros()}U/hr. ")
                rT
            } else {
                rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                setTempBasal(basal, 30, profile, rT, currenttemp)
            }
        } else {
            var insulinReq = smbToGive.toDouble()
            insulinReq = round(insulinReq, 3)
            rT.insulinReq = insulinReq
            //console.error(iob_data.lastBolusTime);
            // minutes since last bolus
            val lastBolusAge = round((systemTime - iob_data.lastBolusTime) / 60000.0, 1)
            //console.error(lastBolusAge);
            //console.error(profile.temptargetSet, target_bg, rT.COB);
            // only allow microboluses with COB or low temp targets, or within DIA hours of a bolus

            if (microBolusAllowed && enableSMB) {
                val microBolus = insulinReq
                rT.reason.append(" insulinReq $insulinReq")
                if (microBolus >= maxSMB) {
                    rT.reason.append("; maxBolus $maxSMB")
                }
                rT.reason.append(". ")

                // allow SMBIntervals between 1 and 10 minutes
                //val SMBInterval = min(10, max(1, profile.SMBInterval))
                val SMBInterval = min(20, max(1, intervalsmb))
                val nextBolusMins = round(SMBInterval - lastBolusAge, 0)
                val nextBolusSeconds = round((SMBInterval - lastBolusAge) * 60, 0) % 60
                if (lastBolusAge > SMBInterval) {
                    if (microBolus > 0) {
                        rT.units = microBolus
                        rT.reason.append("Microbolusing ${microBolus}U. ")
                    }
                } else {
                    rT.reason.append("Waiting " + nextBolusMins + "m " + nextBolusSeconds + "s to microbolus again. ")
                }

            }
            val (localconditionResult, _) = isCriticalSafetyCondition()

            rate = when {
                iob < 0.4 && bg in 90.0..100.0 && delta in 0.0..5.0 && !sportTime                                                 -> profile_current_basal
                iob < 0.6 && bg in 100.0..120.0 && delta in 0.0..6.0 && !sportTime                                                -> profile_current_basal * 2
                iob < 0.8 && bg in 120.0..130.0 && delta in 0.0..6.0 && !sportTime                                                -> profile_current_basal * 4
                bg < 80 && delta < 0                                                                                                          -> 0.0
                bg < 80 && delta >= 0 && iob > 0.0                                                                                            -> profile_current_basal * 0.5
                bg > 180 && delta in -6.0..0.0                                                                                          -> profile_current_basal
                eventualBG < 65 && !snackTime && !mealTime && !lunchTime && !dinnerTime && !highCarbTime && !bfastTime  -> 0.0
                //isMealAnticipated && delta > 15                                                                                               -> calculateBasalRate(basal, profile_current_basal, delta.toDouble())
                eventualBG > 180 && !snackTime && !mealTime && !lunchTime && !dinnerTime && !highCarbTime && !bfastTime && !sportTime  ->calculateBasalRate(basal, profile_current_basal, 5.0)
                snackTime && snackrunTime in 0..30                                                                                      -> calculateBasalRate(basal, profile_current_basal, 4.0)
                mealTime && mealruntime in 0..30                                                                                        -> calculateBasalRate(basal, profile_current_basal, 10.0)
                bfastTime && bfastruntime in 0..30                                                                                      -> calculateBasalRate(basal, profile_current_basal, 10.0)
                bfastTime && bfastruntime in 30..60 && delta > 0                                                                        -> calculateBasalRate(basal, profile_current_basal, delta.toDouble())
                lunchTime && lunchruntime in 0..30                                                                                      -> calculateBasalRate(basal, profile_current_basal, 10.0)
                lunchTime && lunchruntime in 30..60 && delta > 0                                                                        -> calculateBasalRate(basal, profile_current_basal, delta.toDouble())
                dinnerTime && dinnerruntime in 0..30                                                                                    -> calculateBasalRate(basal, profile_current_basal, 10.0)
                dinnerTime && dinnerruntime in 30..60 && delta > 0                                                                      -> calculateBasalRate(basal, profile_current_basal, delta.toDouble())
                highCarbTime && highCarbrunTime in 0..60                                                                                -> calculateBasalRate(basal, profile_current_basal, 10.0)
                bg > 180 && !honeymoon && delta > 0                                                                                           -> calculateBasalRate(basal, profile_current_basal, 10.0)
                recentSteps180Minutes > 2500 && averageBeatsPerMinute180 > averageBeatsPerMinute && bg > 140 && delta > 0 && !sportTime       -> calculateBasalRate(basal, profile_current_basal, delta.toDouble())
                honeymoon && bg in 140.0..169.0 && delta > 0                                                                            -> profile_current_basal
                honeymoon && bg > 170 && delta > 0                                                                                            -> calculateBasalRate(basal, profile_current_basal, delta.toDouble())
                honeymoon && delta > 2 && bg in 90.0..119.0                                                                             -> profile_current_basal
                honeymoon && delta > 0 && bg > 110 && eventualBG > 120 && bg < 160                                                            -> profile_current_basal * delta
                pregnancyEnable && delta > 0 && bg > 110 && !honeymoon                                                                        -> calculateBasalRate(basal, profile_current_basal, 10.0)
                localconditionResult && delta > 1 && bg > 90                                                                                  -> profile_current_basal * delta
                bg > 100 && !conditionResult && eventualBG > 100 && delta in 0.0 .. 4.0 && !sportTime                                   -> profile_current_basal * delta
                else -> 0.0
            }
            rate.let {
                rT.rate = it
                rT.reason.append("${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} AI Force basal because of specific condition: ${round(rate, 2)}U/hr. ")
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }
        }
    }
}
