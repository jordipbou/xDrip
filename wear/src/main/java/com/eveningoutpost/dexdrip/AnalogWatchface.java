package com.eveningoutpost.dexdrip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.wearable.DataMap;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class AnalogWatchface extends CanvasWatchFaceService {
	/*
	 * Updates rate in milliseconds for interactive mode. We update once a second to advance the
	 * second hand.
	 */
	private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.MILLISECONDS.toMillis (33);

	/**
	 * Handler message id for updating the time periodically in interactive mode.
	 */
	private static final int MSG_UPDATE_TIME = 0;

	@Override
	public Engine onCreateEngine () {
		return new Engine ();
	}

	private static class EngineHandler extends Handler {
		private final WeakReference<AnalogWatchface.Engine> mWeakReference;

		public EngineHandler (AnalogWatchface.Engine reference) {
			mWeakReference = new WeakReference<> (reference);
		}

		@Override
		public void handleMessage (Message msg) {
			AnalogWatchface.Engine engine = mWeakReference.get ();
			if (engine != null) {
				switch (msg.what) {
					case MSG_UPDATE_TIME:
						engine.handleUpdateTimeMessage ();
						break;
				}
			}
		}
	}

	private class Engine extends CanvasWatchFaceService.Engine {
		// Receiver of messages from xdrip in the phone
		private Engine.MessageReceiver messageReceiver = new Engine.MessageReceiver ();

		private double mBloodGlucose = 0.0f;
		private float mSlopeRotation = 0.0f;
		private long mLastInsulinMinutesLeft = 0L;
		private long mPreviousLastInsulinTimestamp = Long.MAX_VALUE;
		private double mLowPredictedAt = 0.0f;
		private long mLastUpdate = System.currentTimeMillis ();
		private long mLastCarbsMinutesFrom = Long.MAX_VALUE;
		private long mPreviousLastCarbsTimestamp = Long.MAX_VALUE;
		private long mPreLastCarbsMinutesFrom = Long.MAX_VALUE;
		private long mPreviousPreLastCarbsTimestamp = Long.MAX_VALUE;

		private static final float HOUR_STROKE_WIDTH = 5f;
		private static final float MINUTE_STROKE_WIDTH = 3f;
		private static final float SECOND_TICK_STROKE_WIDTH = 2f;

		private static final float CENTER_GAP_AND_CIRCLE_RADIUS = 4f;

		private static final int SHADOW_RADIUS = 6;
		/* Handler to update the time once a second in interactive mode. */
		private final Handler mUpdateTimeHandler = new EngineHandler (this);
		private Calendar mCalendar;
		private final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver () {
			@Override
			public void onReceive (Context context, Intent intent) {
				mCalendar.setTimeZone (TimeZone.getDefault ());
				invalidate ();
			}
		};
		private boolean mRegisteredTimeZoneReceiver = false;
		private boolean mMuteMode;
		private float mCenterX;
		private float mCenterY;
		private float mSecondHandLength;
		private float sMinuteHandLength;
		private float sHourHandLength;
		/* Colors for all hands (hour, minute, seconds, ticks) based on photo loaded. */
		private int mWatchHandColor;
		private int mWatchHandHighlightColor;
		private int mWatchHandShadowColor;
		private Paint mHourPaint;
		private Paint mMinutePaint;
		private Paint mSecondPaint;
		private Paint mTickAndCirclePaint;

		private Paint mBackgroundPaint;
		private Paint mBitmapsPaint;
		private Paint mCarbsPaint;

		private Bitmap mBackgroundColorBitmap;
		private Bitmap mBackgroundBitmap;
		private Bitmap mWarningBackgroundBitmap;
		private Bitmap mMissedBackgroundBitmap;
		private Bitmap mAmbientBackgroundBitmap;
		private Bitmap mWarningAmbientBackgroundBitmap;
		private Bitmap mMissedAmbientBackgroundBitmap;

		private Bitmap mLowPredictionGuide;

		private Bitmap mSlopeBitmap;
		private Bitmap mAmbientSlopeBitmap;

		private Bitmap mMinutesHandBitmap;
		private Bitmap mMinutesHandBackBitmap;
		private Bitmap mAmbientMinutesHandBitmap;

		private Bitmap mHoursHandBitmap;
		private Bitmap mHoursHandBackBitmap;
		private Bitmap mAmbientHoursHandBitmap;

		private Bitmap mLowPredictionHandBitmap;
		private Bitmap mLowPredictionHandBackBitmap;
		private Bitmap mAmbientLowPredictionHandBitmap;
		private Bitmap mAmbientLowPredictionHandBackBitmap;

		private Bitmap mInsulinGaugeBitmap;
		private Bitmap mInsulinGaugeBackBitmap;
		private Bitmap mInsulinGauge1HourBitmap;
		private Bitmap mInsulinGauge2HoursBitmap;
		private Bitmap mAmbientInsulinGaugeBitmap;
		private Bitmap mAmbientInsulinGaugeBackBitmap;
		private Bitmap mAmbientInsulinGauge1HourBitmap;
		private Bitmap mAmbientInsulinGauge2HoursBitmap;

		//private Bitmap mCarbs1FillerBitmap;
		//private Bitmap mCarbs2FillerBitmap;

		private List<Bitmap> mInsulinHandBitmaps;

		private List<Bitmap> mBG_numbers;
		private List<Bitmap> mAmbientBG_numbers;

		private boolean mAmbient;
		private boolean mLowBitAmbient;
		private boolean mBurnInProtection;

		@Override
		public void onCreate (SurfaceHolder holder) {
			super.onCreate (holder);

			setWatchFaceStyle (new WatchFaceStyle.Builder (AnalogWatchface.this)
				.setAcceptsTapEvents (true)
				.build ());

			mCalendar = Calendar.getInstance ();

			LocalBroadcastManager
				.getInstance (AnalogWatchface.this)
				.registerReceiver (messageReceiver, new IntentFilter (Intent.ACTION_SEND));

			initializeBackground ();
			initializeWatchFace ();

			// Do I need this ?
			// ListenerService.requestData(AnalogWatchface.this);
		}

		private Bitmap initBitmap (int resource) {
			return BitmapFactory.decodeResource (getResources (), resource);
		}

		private void initializeBackground () {
			mBackgroundPaint = new Paint ();

			mBitmapsPaint = new Paint ();
			mBitmapsPaint.setFilterBitmap (true);

			//mBackgroundPaint.setColor (Color.BLACK);

			mBackgroundColorBitmap = initBitmap (R.drawable.black_background);
			mBackgroundBitmap = initBitmap (R.drawable.interactive_background);
			mWarningBackgroundBitmap = initBitmap (R.drawable.interactive_background_warning);
			mMissedBackgroundBitmap = initBitmap (R.drawable.interactive_background_missed);
			mAmbientBackgroundBitmap = initBitmap (R.drawable.ambient_background);
			mWarningAmbientBackgroundBitmap = initBitmap (R.drawable.ambient_background_warning);
			mMissedAmbientBackgroundBitmap = initBitmap (R.drawable.ambient_background_missed);

			mLowPredictionGuide = initBitmap (R.drawable.low_prediction_guide);

			mSlopeBitmap = initBitmap (R.drawable.slope_hand);
			mAmbientSlopeBitmap = initBitmap (R.drawable.ambient_slope_hand);

			mMinutesHandBitmap = initBitmap (R.drawable.minutes_hand);
			mMinutesHandBackBitmap = initBitmap (R.drawable.minutes_hand_back);
			mAmbientMinutesHandBitmap = initBitmap (R.drawable.ambient_minutes_hand);

			mHoursHandBitmap = initBitmap (R.drawable.hours_hand);
			mHoursHandBackBitmap = initBitmap (R.drawable.hours_hand_back);
			mAmbientHoursHandBitmap = initBitmap (R.drawable.ambient_hours_hand);

			mLowPredictionHandBitmap = initBitmap (R.drawable.low_prediction_hand);
			mLowPredictionHandBackBitmap = initBitmap (R.drawable.low_prediction_hand_back);
			mAmbientLowPredictionHandBitmap = initBitmap (R.drawable.ambient_low_prediction_hand);
			mAmbientLowPredictionHandBackBitmap = initBitmap (R.drawable.ambient_low_prediction_hand_back);

			mInsulinGaugeBitmap = initBitmap (R.drawable.insulin_gauge);
			mInsulinGaugeBackBitmap = initBitmap (R.drawable.insulin_gauge_back);
			mInsulinGauge1HourBitmap = initBitmap (R.drawable.insulin_gauge_1hour);
			mInsulinGauge2HoursBitmap = initBitmap (R.drawable.insulin_gauge_2hours);
			mAmbientInsulinGaugeBitmap = initBitmap (R.drawable.ambient_insulin_gauge);
			mAmbientInsulinGaugeBackBitmap = initBitmap (R.drawable.ambient_insulin_gauge_back);
			mAmbientInsulinGauge1HourBitmap = initBitmap (R.drawable.ambient_insulin_gauge_1hour);
			mAmbientInsulinGauge2HoursBitmap = initBitmap (R.drawable.ambient_insulin_gauge_2hours);

			//mCarbs1FillerBitmap = initBitmap (R.drawable.carbs_1_filler);
			//mCarbs2FillerBitmap = initBitmap (R.drawable.carbs_2_filler);

			mBG_numbers = new ArrayList<> ();
			mBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.bg_0));
			mBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.bg_1));
			mBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.bg_2));
			mBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.bg_3));
			mBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.bg_4));
			mBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.bg_5));
			mBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.bg_6));
			mBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.bg_7));
			mBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.bg_8));
			mBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.bg_9));

			mAmbientBG_numbers = new ArrayList<> ();
			mAmbientBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.ambient_bg_0));
			mAmbientBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.ambient_bg_1));
			mAmbientBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.ambient_bg_2));
			mAmbientBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.ambient_bg_3));
			mAmbientBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.ambient_bg_4));
			mAmbientBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.ambient_bg_5));
			mAmbientBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.ambient_bg_6));
			mAmbientBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.ambient_bg_7));
			mAmbientBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.ambient_bg_8));
			mAmbientBG_numbers.add (BitmapFactory.decodeResource (getResources (), R.drawable.ambient_bg_9));

			mInsulinHandBitmaps = new ArrayList<> ();
			mInsulinHandBitmaps.add (BitmapFactory.decodeResource (getResources (), R.drawable.insulin_hand_0));
			mInsulinHandBitmaps.add (BitmapFactory.decodeResource (getResources (), R.drawable.insulin_hand_1));
			mInsulinHandBitmaps.add (BitmapFactory.decodeResource (getResources (), R.drawable.insulin_hand_2));
		}

		private void initializeWatchFace () {
			/* Set defaults for colors */
			mWatchHandColor = Color.WHITE;
			mWatchHandHighlightColor = Color.RED;
			mWatchHandShadowColor = Color.BLACK;

			mHourPaint = new Paint ();
			mHourPaint.setColor (mWatchHandColor);
			mHourPaint.setStrokeWidth (HOUR_STROKE_WIDTH);
			mHourPaint.setAntiAlias (true);
			mHourPaint.setStrokeCap (Paint.Cap.ROUND);
			mHourPaint.setShadowLayer (SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);

			mMinutePaint = new Paint ();
			mMinutePaint.setColor (mWatchHandColor);
			mMinutePaint.setStrokeWidth (MINUTE_STROKE_WIDTH);
			mMinutePaint.setAntiAlias (true);
			mMinutePaint.setStrokeCap (Paint.Cap.ROUND);
			mMinutePaint.setShadowLayer (SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);

			mSecondPaint = new Paint ();
			mSecondPaint.setColor (mWatchHandHighlightColor);
			mSecondPaint.setStrokeWidth (SECOND_TICK_STROKE_WIDTH);
			mSecondPaint.setAntiAlias (true);
			mSecondPaint.setStrokeCap (Paint.Cap.ROUND);
			mSecondPaint.setShadowLayer (SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);

			mTickAndCirclePaint = new Paint ();
			mTickAndCirclePaint.setColor (mWatchHandColor);
			mTickAndCirclePaint.setStrokeWidth (SECOND_TICK_STROKE_WIDTH);
			mTickAndCirclePaint.setAntiAlias (true);
			mTickAndCirclePaint.setStyle (Paint.Style.STROKE);
			mTickAndCirclePaint.setShadowLayer (SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);

			mCarbsPaint = new Paint ();
			mCarbsPaint.setColor (Color.YELLOW);
			mCarbsPaint.setAntiAlias (true);
			mCarbsPaint.setStrokeCap (Paint.Cap.BUTT);
			mCarbsPaint.setStyle (Paint.Style.STROKE);
			mCarbsPaint.setStrokeWidth (7f);
		}

		@Override
		public void onDestroy () {
			mUpdateTimeHandler.removeMessages (MSG_UPDATE_TIME);
			if (messageReceiver != null) {
				LocalBroadcastManager
					.getInstance (AnalogWatchface.this)
					.unregisterReceiver (messageReceiver);
			}
			super.onDestroy ();
		}

		@Override
		public void onPropertiesChanged (Bundle properties) {
			super.onPropertiesChanged (properties);
			mLowBitAmbient = properties.getBoolean (PROPERTY_LOW_BIT_AMBIENT, false);
			mBurnInProtection = properties.getBoolean (PROPERTY_BURN_IN_PROTECTION, false);
		}

		@Override
		public void onTimeTick () {
			super.onTimeTick ();
			invalidate ();
		}

		@Override
		public void onAmbientModeChanged (boolean inAmbientMode) {
			super.onAmbientModeChanged (inAmbientMode);
			mAmbient = inAmbientMode;

			updateWatchHandStyle ();

			/* Check and trigger whether or not timer should be running (only in active mode). */
			updateTimer ();
		}

		private void updateWatchHandStyle () {
			if (mAmbient) {
				mCarbsPaint.setStrokeWidth (1f);
				mCarbsPaint.setColor (Color.WHITE);
			} else {
				mCarbsPaint.setStrokeWidth (7f);
				mCarbsPaint.setColor (Color.YELLOW);
			}
		}

		@Override
		public void onInterruptionFilterChanged (int interruptionFilter) {
			super.onInterruptionFilterChanged (interruptionFilter);
			boolean inMuteMode = (interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE);

			/* Dim display in mute mode. */
			if (mMuteMode != inMuteMode) {
				mMuteMode = inMuteMode;
				mHourPaint.setAlpha (inMuteMode ? 100 : 255);
				mMinutePaint.setAlpha (inMuteMode ? 100 : 255);
				mSecondPaint.setAlpha (inMuteMode ? 80 : 255);
				invalidate ();
			}
		}

		private Bitmap scaleBitmap (Bitmap bitmap, float scale) {
			return Bitmap.createScaledBitmap (
				bitmap,
				(int) (bitmap.getWidth () * scale),
				(int) (bitmap.getHeight () * scale),
				true);
		}

		@Override
		public void onSurfaceChanged (SurfaceHolder holder, int format, int width, int height) {
			super.onSurfaceChanged (holder, format, width, height);

			/*
			 * Find the coordinates of the center point on the screen, and ignore the window
			 * insets, so that, on round watches with a "chin", the watch face is centered on the
			 * entire screen, not just the usable portion.
			 */
			mCenterX = width / 2f;
			mCenterY = height / 2f;

			/*
			 * Calculate lengths of different hands based on watch screen size.
			 */
			mSecondHandLength = (float) (mCenterX * 0.875);
			sMinuteHandLength = (float) (mCenterX * 0.75);
			sHourHandLength = (float) (mCenterX * 0.5);

			/* Scale loaded background image (more efficient) if surface dimensions change. */
			float scale = ((float) width) / (float) mBackgroundBitmap.getWidth ();

			mBackgroundColorBitmap = scaleBitmap (mBackgroundColorBitmap, scale);

			mBackgroundBitmap = scaleBitmap (mBackgroundBitmap, scale);
			mWarningBackgroundBitmap = scaleBitmap (mWarningBackgroundBitmap, scale);
			mMissedBackgroundBitmap = scaleBitmap (mMissedBackgroundBitmap, scale);
			mAmbientBackgroundBitmap = scaleBitmap (mAmbientBackgroundBitmap, scale);
			mWarningAmbientBackgroundBitmap = scaleBitmap (mWarningAmbientBackgroundBitmap, scale);
			mMissedAmbientBackgroundBitmap = scaleBitmap (mMissedAmbientBackgroundBitmap, scale);

			mLowPredictionGuide = scaleBitmap (mLowPredictionGuide, scale);

			// Insulin Gauge
			mInsulinGaugeBitmap = scaleBitmap (mInsulinGaugeBitmap, scale);
			mInsulinGaugeBackBitmap = scaleBitmap (mInsulinGaugeBackBitmap, scale);
			mInsulinGauge1HourBitmap = scaleBitmap (mInsulinGauge1HourBitmap, scale);
			mInsulinGauge2HoursBitmap = scaleBitmap (mInsulinGauge2HoursBitmap, scale);
			mAmbientInsulinGaugeBitmap = scaleBitmap (mAmbientInsulinGaugeBitmap, scale);
			mAmbientInsulinGaugeBackBitmap = scaleBitmap (mAmbientInsulinGaugeBackBitmap, scale);
			mAmbientInsulinGauge1HourBitmap = scaleBitmap (mAmbientInsulinGauge1HourBitmap, scale);
			mAmbientInsulinGauge2HoursBitmap = scaleBitmap (mAmbientInsulinGauge2HoursBitmap, scale);
			// Carbs
			//mCarbs1FillerBitmap = scaleBitmap (mCarbs1FillerBitmap, scale);
			//mCarbs2FillerBitmap = scaleBitmap (mCarbs2FillerBitmap, scale);
			// Minutes
			mMinutesHandBitmap = scaleBitmap (mMinutesHandBitmap, scale);
			mMinutesHandBackBitmap = scaleBitmap (mMinutesHandBackBitmap, scale);
			mAmbientMinutesHandBitmap = scaleBitmap (mAmbientMinutesHandBitmap, scale);
			// Hours
			mHoursHandBitmap = scaleBitmap (mHoursHandBitmap, scale);
			mHoursHandBackBitmap = scaleBitmap (mHoursHandBackBitmap, scale);
			mAmbientHoursHandBitmap = scaleBitmap (mAmbientHoursHandBitmap, scale);
			// Low Prediction
			mLowPredictionHandBitmap = scaleBitmap (mLowPredictionHandBitmap, scale);
			mLowPredictionHandBackBitmap = scaleBitmap (mLowPredictionHandBackBitmap, scale);
			mAmbientLowPredictionHandBitmap = scaleBitmap (mAmbientLowPredictionHandBitmap, scale);
			mAmbientLowPredictionHandBackBitmap = scaleBitmap (mAmbientLowPredictionHandBackBitmap, scale);

			// Slope
			mSlopeBitmap = scaleBitmap (mSlopeBitmap, scale);

			// TODO: Scale all the bitmaps !! Should we maintain an original bitmap before
			// scaling ?
		}

		/**
		 * Captures tap event (and tap type). The {@link WatchFaceService#TAP_TYPE_TAP} case can be
		 * used for implementing specific logic to handle the gesture.
		 */
		@Override
		public void onTapCommand (int tapType, int x, int y, long eventTime) {
			switch (tapType) {
				case TAP_TYPE_TOUCH:
					// The user has started touching the screen.
					break;
				case TAP_TYPE_TOUCH_CANCEL:
					// The user has started a different gesture or otherwise cancelled the tap.
					break;
				case TAP_TYPE_TAP:
					// The user has completed the tap gesture.
					// TODO: Add code to handle the tap gesture.
					break;
			}
			invalidate ();
		}

		@Override
		public void onDraw (Canvas canvas, Rect bounds) {
			long now = System.currentTimeMillis ();
			mCalendar.setTimeInMillis (now);

			canvas.drawColor (Color.BLACK);
			drawCarbs (canvas);
			drawBackground (canvas);
			drawWatchFace (canvas);
		}

		private void drawBackgroundBitmap (Canvas canvas, Bitmap bitmap) {
			drawBackgroundBitmap (canvas, bitmap, mBackgroundPaint);
		}

		private void drawBackgroundBitmap (Canvas canvas, Bitmap bitmap, Paint paint) {
			canvas.drawBitmap (bitmap, 0, 0, paint);
		}

		private void drawRotatingFromTopBitmap (Canvas canvas, Bitmap bitmap, float centerX, Paint paint) {
			canvas.drawBitmap (
				bitmap,
				centerX - (bitmap.getWidth () / 2.0f),
				0f,
				paint);
		}

		private void drawFrontBackCenteredBitmap (Canvas c, Bitmap f, Bitmap b, float cX, float cY, Paint p) {
			c.drawBitmap (f, cX - (f.getWidth () / 2.0f), cY - f.getHeight (), p);
			if (b != null) {
				c.drawBitmap (b, cX - (b.getWidth () / 2.0f), cY, p);
			}
		}

		private void drawCenteredBitmap (Canvas c, Bitmap b, float cX, float cY, Paint p) {
			c.drawBitmap (b, cX - (b.getWidth () / 2.0f), cY - (b.getHeight () / 2.0f), p);
		}

		private void drawCarbs (Canvas canvas) {
			float carbs1X = mCenterX / 17.0f;
			float carbs1Y = mCenterY / 17.0f;
			float carbs2X = mCenterX / 10.0f;
			float carbs2Y = mCenterY / 10.0f;

			// Carbs
			float carbs1end = 180.0f - (mLastCarbsMinutesFrom * 6f); // - angle of minuts passed
			float carbs2end = 180.0f - (mPreLastCarbsMinutesFrom * 6f);

			// Carbohidrates time
			if (mLastCarbsMinutesFrom <= 30) {
				canvas.drawArc (
					carbs1X, carbs1Y,
					canvas.getWidth () - carbs1X, canvas.getHeight () - carbs1Y,
					-180.0f,
					carbs1end,
					false,
					mCarbsPaint);
			}

			if (mPreLastCarbsMinutesFrom <= 30) {
				canvas.drawArc (
					carbs2X, carbs2Y,
					canvas.getWidth () - carbs2X, canvas.getHeight () - carbs2Y,
					-180.0f,
					carbs2end,
					false,
					mCarbsPaint);
			}
		}

		private void drawBackground (Canvas canvas) {
			long minutesFromLastUpdate = (System.currentTimeMillis () - mLastUpdate) / (60 * 1000);
			if (mAmbient /* && (mLowBitAmbient || mBurnInProtection) */) {
				if (minutesFromLastUpdate >= 4) {
					drawBackgroundBitmap (canvas, mMissedAmbientBackgroundBitmap);
				} else if (minutesFromLastUpdate >= 2) {
					drawBackgroundBitmap (canvas, mWarningAmbientBackgroundBitmap);
				} else {
					drawBackgroundBitmap (canvas, mAmbientBackgroundBitmap);
				}
			} else {
				if (minutesFromLastUpdate >= 4) {
					drawBackgroundBitmap (canvas, mMissedBackgroundBitmap);
				} else if (minutesFromLastUpdate >= 2) {
					drawBackgroundBitmap (canvas, mWarningBackgroundBitmap);
				} else {
					drawBackgroundBitmap (canvas, mBackgroundBitmap);
				}
			}
		}

		private void drawWatchFace (Canvas canvas) {
			/*
			 * These calculations reflect the rotation in degrees per unit of time, e.g.,
			 * 360 / 60 = 6 and 360 / 12 = 30.
			 */
			final float seconds =
				(mCalendar.get (Calendar.SECOND) + mCalendar.get (Calendar.MILLISECOND) / 1000f);
			final float secondsRotation = seconds * 6f;

			final float minutesRotation = mCalendar.get (Calendar.MINUTE) * 6f;

			final float hourHandOffset = mCalendar.get (Calendar.MINUTE) / 2f;
			final float hoursRotation = (mCalendar.get (Calendar.HOUR) * 30) + hourHandOffset;

			final long insulinHoursLeft = (long) Math.floor ((double)mLastInsulinMinutesLeft / 60.0f);
			final long insulinMinutesLeft = mLastInsulinMinutesLeft % 60;
			float insulinTimeLeftRotation;
			if (insulinMinutesLeft >= 0) {
				insulinTimeLeftRotation = insulinMinutesLeft * 6f;
			} else {
				insulinTimeLeftRotation = 0.0f;
			}

			float lowPredictionHandRotation = 0.0f;
			if (mLowPredictedAt > 0.0f && mLowPredictedAt < 60) {
				lowPredictionHandRotation = (float)(mLowPredictedAt * 6f);
			}


			// Draw blood glucose numbers (no rotation needed)
			int hundreds_digit = (((int)mBloodGlucose) / 100);
			int tens_digit = (((int)mBloodGlucose) % 100) / 10;
			int units_digit = (((int)mBloodGlucose) % 100) % 10;

			float number_separation = mCenterX * 0.05f;

			/*
			 * Save the canvas state before we can begin to rotate it.
			 */
			canvas.save ();

			canvas.rotate (insulinTimeLeftRotation, mCenterX, mCenterY);
			if (insulinHoursLeft > 1) {
				drawCenteredBitmap (
					canvas,
					mAmbient ? mAmbientInsulinGauge2HoursBitmap : mInsulinGauge2HoursBitmap,
					mCenterX, mCenterY,
					mBitmapsPaint);
			}
			if (insulinHoursLeft > 0) {
				drawCenteredBitmap (
					canvas,
					mAmbient ? mAmbientInsulinGauge1HourBitmap : mInsulinGauge1HourBitmap,
					mCenterX, mCenterY,
					mBitmapsPaint);
			}
			if (mLastInsulinMinutesLeft >= 0) {
				drawFrontBackCenteredBitmap (
					canvas,
					mAmbient ? mAmbientInsulinGaugeBitmap : mInsulinGaugeBitmap,
					mAmbient ? mAmbientInsulinGaugeBackBitmap : mInsulinGaugeBackBitmap,
					mCenterX, mCenterY,
					mBitmapsPaint);
			}

			// Restore original rotation for numbers
			canvas.rotate (-insulinTimeLeftRotation, mCenterX, mCenterY);

			if (mAmbient) {
				float x = mCenterX + (mCenterX * 0.75f) - mAmbientBG_numbers.get (units_digit).getWidth ();
				float y = mCenterY - (mAmbientBG_numbers.get (0).getHeight () / 2.0f);
				canvas.drawBitmap (
					mAmbientBG_numbers.get (units_digit),
					x,
					y,
					mBitmapsPaint);

				x = x - mBG_numbers.get (tens_digit).getWidth () - number_separation;
				canvas.drawBitmap (
					mAmbientBG_numbers.get (tens_digit),
					x,
					y,
					mBitmapsPaint);

				x = x - mBG_numbers.get (hundreds_digit).getWidth () - number_separation;
				canvas.drawBitmap (
					mAmbientBG_numbers.get (hundreds_digit),
					x,
					y,
					mBitmapsPaint);
			} else {
				float x = mCenterX + (mCenterX * 0.75f) - mBG_numbers.get (units_digit).getWidth ();
				float y = mCenterY - (mBG_numbers.get (0).getHeight () / 2.0f);
				canvas.drawBitmap (
					mBG_numbers.get (units_digit),
					x,
					y,
					mBitmapsPaint);

				x = x - mBG_numbers.get (tens_digit).getWidth () - number_separation;
				canvas.drawBitmap (
					mBG_numbers.get (tens_digit),
					x,
					y,
					mBitmapsPaint);

				x = x - mBG_numbers.get (hundreds_digit).getWidth () - number_separation;
				canvas.drawBitmap (
					mBG_numbers.get (hundreds_digit),
					x,
					y,
					mBitmapsPaint);
			}

			canvas.rotate (hoursRotation, mCenterX, mCenterY);
			drawFrontBackCenteredBitmap (
				canvas,
				mAmbient ? mAmbientHoursHandBitmap : mHoursHandBitmap,
				mAmbient ? null : mHoursHandBackBitmap,
				mCenterX, mCenterY,
				mBitmapsPaint
			);

			canvas.rotate (minutesRotation - hoursRotation, mCenterX, mCenterY);
			drawFrontBackCenteredBitmap (
				canvas,
				mAmbient ? mAmbientMinutesHandBitmap : mMinutesHandBitmap,
				mAmbient ? null : mMinutesHandBackBitmap,
				mCenterX, mCenterY,
				mBitmapsPaint
			);

			canvas.rotate (lowPredictionHandRotation - minutesRotation, mCenterX, mCenterY);
			if (mLowPredictedAt > 0.0f) {
				drawFrontBackCenteredBitmap (
					canvas,
					mAmbient ? mAmbientLowPredictionHandBitmap : mLowPredictionHandBitmap,
					mAmbient ? mAmbientLowPredictionHandBackBitmap : mLowPredictionHandBackBitmap,
					mCenterX, mCenterY,
					mBitmapsPaint);
			}

			canvas.rotate (mSlopeRotation - lowPredictionHandRotation, mCenterX, mCenterY);
			if (mAmbient) {
				drawRotatingFromTopBitmap (canvas, mAmbientSlopeBitmap, mCenterX, mBitmapsPaint);

			} else {
				drawRotatingFromTopBitmap (canvas, mSlopeBitmap, mCenterX, mBitmapsPaint);
			}

			/*
			 * Ensure the "seconds" hand is drawn only when we are in interactive mode.
			 * Otherwise, we only update the watch face once a minute.
			 */
			if (!mAmbient) {
				canvas.rotate (secondsRotation - mSlopeRotation, mCenterX, mCenterY);
				canvas.drawLine (
					mCenterX,
					mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
					mCenterX,
					mCenterY - mSecondHandLength,
					mSecondPaint);

				canvas.drawCircle (
					mCenterX,
					mCenterY,
					CENTER_GAP_AND_CIRCLE_RADIUS,
					mTickAndCirclePaint);
			}

			/* Restore the canvas" original orientation. */
			canvas.restore ();
		}

		@Override
		public void onVisibilityChanged (boolean visible) {
			super.onVisibilityChanged (visible);

			if (visible) {
				registerReceiver ();
				/* Update time zone in case it changed while we weren"t visible. */
				mCalendar.setTimeZone (TimeZone.getDefault ());
				invalidate ();
			} else {
				unregisterReceiver ();
			}

			/* Check and trigger whether or not timer should be running (only in active mode). */
			updateTimer ();
		}

		private void registerReceiver () {
			if (mRegisteredTimeZoneReceiver) {
				return;
			}
			mRegisteredTimeZoneReceiver = true;
			IntentFilter filter = new IntentFilter (Intent.ACTION_TIMEZONE_CHANGED);
			AnalogWatchface.this.registerReceiver (mTimeZoneReceiver, filter);
		}

		private void unregisterReceiver () {
			if (!mRegisteredTimeZoneReceiver) {
				return;
			}
			mRegisteredTimeZoneReceiver = false;
			AnalogWatchface.this.unregisterReceiver (mTimeZoneReceiver);
		}

		/**
		 * Starts/stops the {@link #mUpdateTimeHandler} timer based on the state of the watch face.
		 */
		private void updateTimer () {
			mUpdateTimeHandler.removeMessages (MSG_UPDATE_TIME);
			if (shouldTimerBeRunning ()) {
				mUpdateTimeHandler.sendEmptyMessage (MSG_UPDATE_TIME);
			}
		}

		/**
		 * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer
		 * should only run in active mode.
		 */
		private boolean shouldTimerBeRunning () {
			return isVisible () && !mAmbient;
		}

		/**
		 * Handle updating the time periodically in interactive mode.
		 */
		private void handleUpdateTimeMessage () {
			invalidate ();
			if (shouldTimerBeRunning ()) {
				long timeMs = System.currentTimeMillis ();
				long delayMs = INTERACTIVE_UPDATE_RATE_MS
					- (timeMs % INTERACTIVE_UPDATE_RATE_MS);
				mUpdateTimeHandler.sendEmptyMessageDelayed (MSG_UPDATE_TIME, delayMs);
			}
		}

		public class MessageReceiver extends BroadcastReceiver {
			@Override
			public void onReceive(Context context, Intent intent) {
				DataMap dataMap;
				Bundle bundle = intent.getBundleExtra("data");
				if (bundle != null) {
					dataMap = DataMap.fromBundle(bundle);

					mBloodGlucose = dataMap.getDouble ("sgvDouble");

					String slopeArrow = dataMap.getString ("slopeArrow");
					if (slopeArrow == null) slopeArrow = "";
					if (slopeArrow.equals (com.eveningoutpost.dexdrip.importedlibraries.dexcom.Dex_Constants.TREND_ARROW_VALUES.DOUBLE_DOWN.Symbol ())) {
						mSlopeRotation = 180.0f;
					} else if (slopeArrow.equals (com.eveningoutpost.dexdrip.importedlibraries.dexcom.Dex_Constants.TREND_ARROW_VALUES.SINGLE_DOWN.Symbol ())) {
						mSlopeRotation = 150.0f;
					} else if (slopeArrow.equals (com.eveningoutpost.dexdrip.importedlibraries.dexcom.Dex_Constants.TREND_ARROW_VALUES.DOWN_45.Symbol ())) {
						mSlopeRotation = 120.0f;
					} else if (slopeArrow.equals (com.eveningoutpost.dexdrip.importedlibraries.dexcom.Dex_Constants.TREND_ARROW_VALUES.FLAT.Symbol ())) {
						mSlopeRotation = 90.0f;
					} else if (slopeArrow.equals (com.eveningoutpost.dexdrip.importedlibraries.dexcom.Dex_Constants.TREND_ARROW_VALUES.UP_45.Symbol ())) {
						mSlopeRotation = 60.0f;
					} else if (slopeArrow.equals (com.eveningoutpost.dexdrip.importedlibraries.dexcom.Dex_Constants.TREND_ARROW_VALUES.SINGLE_UP.Symbol ())) {
						mSlopeRotation = 30.0f;
					} else if (slopeArrow.equals (com.eveningoutpost.dexdrip.importedlibraries.dexcom.Dex_Constants.TREND_ARROW_VALUES.DOUBLE_UP.Symbol ())) {
						mSlopeRotation = 0.0f;
					} else {
						mSlopeRotation = 90.0f;
					}

					long lastInsulinTimestamp = dataMap.getLong ("lastInsulinTimestamp");
					long minutesFromPrevious = (System.currentTimeMillis () - mPreviousLastInsulinTimestamp) / 60000;
					// Sometimes lastInsulinTimestamp gets a 0 value, let's ignore it for one update
					if (lastInsulinTimestamp <= 0 && minutesFromPrevious >= 1) {
						lastInsulinTimestamp = mPreviousLastInsulinTimestamp;
						mPreviousLastInsulinTimestamp = Long.MAX_VALUE;
					} else if (lastInsulinTimestamp >= 0){
						mPreviousLastInsulinTimestamp = lastInsulinTimestamp;
					}
					long minutesFrom = (System.currentTimeMillis () - lastInsulinTimestamp) / 60000;
					// This is fixed for Humalog insulin, it should be possible to check
					// duration based on insulin type
					mLastInsulinMinutesLeft = 175 - minutesFrom;

					double low_occurs_at = dataMap.getDouble ("lowPredictedAt");
					mLowPredictedAt = (low_occurs_at - System.currentTimeMillis ()) / 60000;

					long last_carbs_timestamp = dataMap.getLong ("lastCarbs");
					long prelast_carbs_timestamp = dataMap.getLong ("prelastCarbs");
					if (last_carbs_timestamp <= 0 && mLastCarbsMinutesFrom < 30) {
						last_carbs_timestamp = mPreviousLastCarbsTimestamp;
					} else {
						mPreviousLastCarbsTimestamp = last_carbs_timestamp;
					}
					if (prelast_carbs_timestamp <= 0 && mPreLastCarbsMinutesFrom < 30) {
						prelast_carbs_timestamp = mPreviousPreLastCarbsTimestamp;
					} else {
						mPreviousPreLastCarbsTimestamp = prelast_carbs_timestamp;
					}
					mLastCarbsMinutesFrom = (System.currentTimeMillis () - last_carbs_timestamp) / 60000;
					mPreLastCarbsMinutesFrom = (System.currentTimeMillis () - prelast_carbs_timestamp) / 60000;
					Log.d ("JPBOU", "Last carbs timestamp: " + last_carbs_timestamp);
					Log.d ("JPBOU", "Last carbs minutes from: " + mLastCarbsMinutesFrom);
					Log.d ("JPBOU", "Pre last carbs timestamp: " + prelast_carbs_timestamp);
					Log.d ("JPBOU", "Pre last carbs minutes from: " + mPreLastCarbsMinutesFrom);

					mLastUpdate = System.currentTimeMillis ();
					//setDelta(dataMap.getString("delta"));
					//setDatetime(dataMap.getDouble("timestamp"));
					//mExtraStatusLine = dataMap.getString("extra_status_line");
					//addToWatchSet(dataMap);

					invalidate();
				} else {
					Log.d ("JPBOU", "No data to extract");
				}
			}
		}
	}
}