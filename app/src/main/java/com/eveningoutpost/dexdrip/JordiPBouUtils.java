package com.eveningoutpost.dexdrip;

import android.content.Context;
import android.content.Intent;
import android.text.format.DateFormat;
import android.util.Log;

import com.activeandroid.query.Select;
import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.Libre2RawValue;
import com.eveningoutpost.dexdrip.models.Sensor;
import com.eveningoutpost.dexdrip.models.Treatments;
import com.eveningoutpost.dexdrip.utilitymodels.BgGraphBuilder;

import java.text.DecimalFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.eveningoutpost.dexdrip.xdrip.getAppContext;

public class JordiPBouUtils {
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

		getAppContext ().sendBroadcast(intent); // JPBOU::For Android 12::, "jordipbou.receivebg");
	}

	public static void sendLibre2RawValueBroadcastIntent (Libre2RawValue raw) {
		Log.d ("JPBOU", "Sending libre2 raw value [" + String.valueOf (raw.glucose) + "]");
		Intent intent = new Intent();
		intent.setAction("org.jordipbou.wondersync.LIBRE2_RAW_VALUE");
		intent.putExtra("value", raw.glucose);
		intent.putExtra ("timestamp", raw.timestamp);
		intent.putExtra("serial", raw.serial);
		getAppContext ().sendBroadcast(intent);
	}

	public static void processValues(Libre2RawValue currentValue) {
		if (Sensor.currentSensor() == null) {
			Sensor.create(currentValue.timestamp, currentValue.serial);
		}

		// TODO: Is it possible to do this on some kind of transaction to
		// TODO: not make alarms appear when deleting and before inserting ?!

		// Remove previous raw value
		//if (BgReading.last () != null) BgReading.last ().delete ();
		//BgReading last_raw = BgReading.last ();

		// Get two averaged values
		List<BgReading> latest = BgReading.latest (3);

		if (latest.size() > 0) {
			BgReading last = latest.get (0);
			last.delete ();
		}
		BgReading prevlast = null;
		if (latest.size() > 1) prevlast = latest.get (1);
		BgReading prevprevlast = null;
		if (latest.size() > 2) prevlast = latest.get (2);
		if (prevlast != null && prevprevlast != null) {
			// TODO: And prevlast timestamp is not more than 10.9 minutes from now !!
			if ((prevlast.timestamp - prevprevlast.timestamp) < (5 * 60 * 1000)) {
				prevlast.delete ();
			}
		}

		// Always insert averaged one five minutes before, it will be deleted if its distance
		// to previous one is less than 5 minutes.
		List<Libre2RawValue> last20minutes = Libre2RawValue.last20Minutes ();
		last20minutes.add (currentValue);
		double value = calculateWeightedAverage (last20minutes, currentValue.timestamp);
		BgReading avg_bg = BgReading.bgReadingInsertLibre2 (value, currentValue.timestamp - (45 * 6 * 1000), currentValue.glucose);
		BgReading new_bg = BgReading.bgReadingInsertLibre2 (currentValue.glucose, currentValue.timestamp, currentValue.glucose);
		JordiPBouUtils.sendBestGlucoseBroadcastIntent (new_bg);
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
