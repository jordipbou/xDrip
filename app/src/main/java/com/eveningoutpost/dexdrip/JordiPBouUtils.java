package com.eveningoutpost.dexdrip;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.text.format.DateFormat;
import android.util.Log;

import com.activeandroid.ActiveAndroid;
import com.activeandroid.query.Select;
import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.Libre2RawValue;
import com.eveningoutpost.dexdrip.models.Sensor;
import com.eveningoutpost.dexdrip.models.Treatments;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.utilitymodels.BgGraphBuilder;
import com.eveningoutpost.dexdrip.utils.DexCollectionType;

import java.text.DecimalFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.eveningoutpost.dexdrip.utilitymodels.VehicleMode.sendBroadcast;
import static com.eveningoutpost.dexdrip.xdrip.getAppContext;

public class JordiPBouUtils {
	private static final String TAG = "JPBOU::XDRIP::UTILS";

	// Helper that allows sending BroadcastIntents to specific packages
	// by searching with packageManager which packages will accept that
	// message.
	public static void sendBroadcast(Context context, Intent intent) {
		PackageManager packageManager = context.getPackageManager();
		List<ResolveInfo> infos = packageManager.queryBroadcastReceivers(intent, 0);
		for (ResolveInfo info : infos) {
			ComponentName cn = new ComponentName(info.activityInfo.packageName,
				info.activityInfo.name);
			intent.setComponent(cn);
			Log.d(TAG, "Sending intent " + intent.getAction () + " to package " + info.activityInfo.packageName);
			context.sendBroadcast(intent);
		}
	}
	public static void sendBestGlucoseBroadcastIntent (BgReading bgr) {
		Log.d ("JPBOU", "Sending xDrip glucose value [" + String.valueOf (bgr.calculated_value) + ", " + bgr.slopeName () + "]");
		Intent intent = new Intent ();
		intent.setAction("org.jordipbou.rawnightwatch.XDRIP_GLUCOSE_VALUE");
		intent.putExtra("value", bgr.calculated_value);
		intent.putExtra ("slope_name", bgr.slopeName ());
		intent.putExtra ("timestamp", bgr.timestamp);
		intent.putExtra ("lowPredictedAt", BgGraphBuilder.getCurrentLowOccursAt ());
		final Treatments last_insulin = Treatments.lastInsulin ();
		intent.putExtra ("lastInsulinTimestamp", last_insulin != null ? last_insulin.timestamp : 0L);
		final List<Treatments> last_carbs = Treatments.last2Carbs ();
		if (last_carbs != null && last_carbs.size () > 0) intent.putExtra ("lastCarbs", last_carbs.get (0).timestamp);
		else intent.putExtra ("lastCarbs", 0L);
		if (last_carbs != null && last_carbs.size () > 1) intent.putExtra ("prelastCarbs", last_carbs.get (1).timestamp);
		else intent.putExtra ("prelastCarbs", 0L);
		intent.putExtra("iob", Treatments.getCurrentIoB ());

		sendBroadcast(getAppContext(), intent);
	}

	public static void sendLibre2RawValueBroadcastIntent (Libre2RawValue raw) {
		Log.d ("JPBOU", "Sending libre2 raw value [" + String.valueOf (raw.glucose) + "]");
		Intent intent = new Intent();
		intent.setAction("org.jordipbou.wondersync.LIBRE2_RAW_VALUE");
		intent.putExtra("value", raw.glucose);
		intent.putExtra ("timestamp", raw.timestamp);
		intent.putExtra("serial", raw.serial);

		sendBroadcast(getAppContext (), intent);
	}

	public static void processValues(Libre2RawValue currentValue) {
		if (Sensor.currentSensor () == null) {
			Sensor.create (currentValue.timestamp, currentValue.serial);
		}

		// Let's use a transaction to avoid xDrip doing things while I delete and save
		// values.

		BgReading new_bg = null;

		ActiveAndroid.beginTransaction();

		try {
			List<BgReading> latest = BgReading.latest (3);

			if (latest.size () > 0) {
				long now = new Date ().getTime ();
				// I only need to delete previous data if we have previous data.
				BgReading last = null;
				BgReading prelast = null;
				BgReading preprelast = null;
				last = latest.get (0);
				if (latest.size () > 1) prelast = latest.get (1);
				if (latest.size () > 2) preprelast = latest.get (2);
				// I delete the current one only if I have at least one
				// previous value in the current five minutes window.
				// Glucose values must be accumulated somehow.
				if (prelast != null && prelast.timestamp > (50 * 60 * 1000)) last.delete ();
				// I delete the previous one if I have at least three values
				// and the distance from the previous one to the previous to the
				// previous is less than five minutes.
				if (preprelast != null && prelast != null
					&& (prelast.timestamp - preprelast.timestamp) < (50 * 6 * 1000))
					prelast.delete ();
			}
			// Now, an averaged value will always be added five minutes from now,
			// to compensate the five minute delay that normally xDrip has.
			List<Libre2RawValue> last20minutes = Libre2RawValue.last20Minutes ();
			last20minutes.add (currentValue);
			double value = calculateWeightedAverage (last20minutes, currentValue.timestamp);
			bgReadingInsertLibre2 (value, currentValue.timestamp - (50 * 6 * 1000), currentValue.glucose);

			// Insert current value (without calculations and processing)
			new_bg = bgReadingInsertLibre2 (currentValue.glucose, currentValue.timestamp, currentValue.glucose);

			// End transaction
			ActiveAndroid.setTransactionSuccessful ();
		} finally {
			ActiveAndroid.endTransaction ();
			if (new_bg != null) {
				// Perform required calculations
				// One of this two function calls make xDrip very unresponsive,
				// but they were on original LibreReceiver (inside original bgReadingInsertLibre2)
				// so I just make them after deleting/inserting (instead of on both inserts).
				new_bg.perform_calculations ();
				new_bg.postProcess (false);

				// And send value out to WearOS and to WonderNight
				JordiPBouUtils.sendBestGlucoseBroadcastIntent (new_bg);
			}
		}
	}

	// This function is copied from BgReading to allow inserting Libre2Data without
	// doing more calculations and without window deduplication check.
	public static synchronized BgReading bgReadingInsertLibre2(double calculated_value, long timestamp, double raw_data) {

		final Sensor sensor = Sensor.currentSensor();
		if (sensor == null) {
			UserError.Log.w(TAG, "No sensor, ignoring this bg reading");
			return null;
		}

		// As the function updateCalculatedValueWitthinMinMax of BgReading is not public
		// and we don't use calibrations I just copied the first if branch.
		final BgReading bgReading = new BgReading();
		UserError.Log.d(TAG, "create: No calibration yet");
		bgReading.sensor = sensor;
		bgReading.sensor_uuid = sensor.uuid;
		bgReading.raw_data = raw_data;
		bgReading.age_adjusted_raw_value = raw_data;
		bgReading.filtered_data = raw_data;
		bgReading.timestamp = timestamp;
		bgReading.uuid = UUID.randomUUID().toString();
		bgReading.calculated_value = calculated_value;
		bgReading.calculated_value_slope = 0;
		bgReading.hide_slope = false;
		bgReading.appendSourceInfo("Libre2 Native");
		bgReading.find_slope ();

		bgReading.save();

		return bgReading;
	}

	public static List<BgReading> lastXMinutes(double minutes) {
		return new Select()
			.from(BgReading.class)
			.where("timestamp >= " + ((new Date ().getTime ()) - (60000 * minutes)))
			.orderBy("timestamp desc")
			.execute();
	}

	private static long SMOOTHING_DURATION = TimeUnit.MINUTES.toMillis(25);

	private static double calculateWeightedAverage(List<Libre2RawValue> rawValues, long now) {
		double sum = 0;
		double weightSum = 0;
		DecimalFormat longformat = new DecimalFormat( "#,###,###,##0.00" );

		String libre_calc_doku="";
		for (Libre2RawValue rawValue : rawValues) {
			double weight = 1 - ((now - rawValue.timestamp) / (double) SMOOTHING_DURATION);
			sum += rawValue.glucose * weight;
			weightSum += weight;
			libre_calc_doku += DateFormat.format("kk:mm:ss :",rawValue.timestamp) + " w:" + longformat.format(weight) +" raw: " + rawValue.glucose  + "\n" ;
		}
		return Math.round(sum / weightSum);
	}
}
