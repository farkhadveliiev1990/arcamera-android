package com.shindev.huaweicamera.camfilter;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.Toast;

import com.shindev.huaweicamera.widget.AutoFitTextureView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Vector;

public class CameraController2 extends CameraController {
	private Context context = null;
	private CameraDevice camera = null;
	
	private AutoFitTextureView mTextureView;

    private int cameraID = 0;
	private String cameraIdS = null;

	private CameraCharacteristics characteristics;
	private CameraCaptureSession captureSession = null;
	private CaptureRequest.Builder previewBuilder = null;

	private SurfaceTexture texture = null;
	private Surface surface_texture = null;
	private HandlerThread thread = null;
	Handler handler = null;

	private MediaRecorder mMediaRecorder;

	private HandlerThread mBackgroundThread;

	private Handler mBackgroundHandler;
	
	private List<Integer> zoom_ratios = null;
	private int current_zoom_value = 0;
	
	private CameraController.ErrorCallback preview_error_cb = null;	
	private CameraController.AutoFocusCallback autofocus_cb = null;
	private CameraController.PictureCallback jpeg_cb = null;
	private CameraController.ErrorCallback take_picture_error_cb = null;
	
	private CameraController.FaceDetectionListener face_detection_listener = null;
	
	private ImageReader imageReader = null;

	private int preview_width = 0;
	private int preview_height = 0;

	private int picture_width = 0;
	private int picture_height = 0;

	private static final int STATE_NORMAL = 0;
	private static final int STATE_WAITING_AUTOFOCUS = 1;
	private static final int STATE_WAITING_PRECAPTURE_START = 2;
	private static final int STATE_WAITING_PRECAPTURE_DONE = 3;
	private int state = STATE_NORMAL;

	private MediaActionSound media_action_sound = new MediaActionSound();
	private boolean sounds_enabled = true;

	private boolean capture_result_has_iso = false;
	private int capture_result_iso = 0;
	private boolean capture_result_has_exposure_time = false;
	private long capture_result_exposure_time = 0;
	private boolean capture_result_has_frame_duration = false;
	private long capture_result_frame_duration = 0;

	private class CameraSettings {
		// keys that we need to store, to pass to the stillBuilder, but doesn't need to be passed to previewBuilder (should set sensible defaults)
		private int rotation = 0;
		private Location location = null;
		private byte jpeg_quality = 90;

		// keys that we have passed to the previewBuilder, that we need to store to also pass to the stillBuilder (should set sensible defaults, or use a has_ boolean if we don't want to set a default)
		private int scene_mode = CameraMetadata.CONTROL_SCENE_MODE_DISABLED;
		private int color_effect = CameraMetadata.CONTROL_EFFECT_MODE_OFF;
		private int white_balance = CameraMetadata.CONTROL_AWB_MODE_AUTO;
		private String flash_value = "flash_off";
		private boolean has_iso = false;
		//private int ae_mode = CameraMetadata.CONTROL_AE_MODE_ON;
		//private int flash_mode = CameraMetadata.FLASH_MODE_OFF;
		private int iso = 0;
		private long exposure_time = 1000000000l/30;
		private Rect scalar_crop_region = null; // no need for has_scalar_crop_region, as we can set to null instead
		private boolean has_ae_exposure_compensation = false;
		private int ae_exposure_compensation = 0;
		private boolean has_af_mode = false;
		private int af_mode = CaptureRequest.CONTROL_AF_MODE_AUTO;
		private float focus_distance = 0.0f; // actual value passed to camera device (set to 0.0 if in infinity mode)
		private float focus_distance_manual = 0.0f; // saved setting when in manual mode
		private boolean ae_lock = false;
		private MeteringRectangle [] af_regions = null; // no need for has_scalar_crop_region, as we can set to null instead
		private MeteringRectangle [] ae_regions = null; // no need for has_scalar_crop_region, as we can set to null instead
		private boolean has_face_detect_mode = false;
		private int face_detect_mode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF;
		private boolean video_stabilization = false;

		private void setupBuilder(CaptureRequest.Builder builder, boolean is_still) {
			setSceneMode(builder);
			setColorEffect(builder);
			setWhiteBalance(builder);
			setAEMode(builder, is_still);
			setCropRegion(builder);
			setExposureCompensation(builder);
			setFocusMode(builder);
			setFocusDistance(builder);
			setAutoExposureLock(builder);
			setAFRegions(builder);
			setAERegions(builder);
			setFaceDetectMode(builder);
			setVideoStabilization(builder);

			if( is_still ) {
				if( location != null ) {
					builder.set(CaptureRequest.JPEG_GPS_LOCATION, location);
				}
				builder.set(CaptureRequest.JPEG_ORIENTATION, rotation);
				builder.set(CaptureRequest.JPEG_QUALITY, jpeg_quality);
			}
		}

		private boolean setSceneMode(CaptureRequest.Builder builder) {
			if( builder.get(CaptureRequest.CONTROL_SCENE_MODE) == null && scene_mode == CameraMetadata.CONTROL_SCENE_MODE_DISABLED ) {
				// can leave off
			}
			else if( builder.get(CaptureRequest.CONTROL_SCENE_MODE) == null || builder.get(CaptureRequest.CONTROL_SCENE_MODE) != scene_mode ) {
				if( scene_mode == CameraMetadata.CONTROL_SCENE_MODE_DISABLED ) {
					builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
				}
				else {
					builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE);
				}
				builder.set(CaptureRequest.CONTROL_SCENE_MODE, scene_mode);
				return true;
			}
			return false;
		}

		private boolean setColorEffect(CaptureRequest.Builder builder) {
			if( builder.get(CaptureRequest.CONTROL_EFFECT_MODE) == null && color_effect == CameraMetadata.CONTROL_EFFECT_MODE_OFF ) {
				// can leave off
			}
			else if( builder.get(CaptureRequest.CONTROL_EFFECT_MODE) == null || builder.get(CaptureRequest.CONTROL_EFFECT_MODE) != color_effect ) {
				builder.set(CaptureRequest.CONTROL_EFFECT_MODE, color_effect);
				return true;
			}
			return false;
		}

		private boolean setWhiteBalance(CaptureRequest.Builder builder) {
			if( builder.get(CaptureRequest.CONTROL_AWB_MODE) == null && white_balance == CameraMetadata.CONTROL_AWB_MODE_AUTO ) {
				// can leave off
			}
			else if( builder.get(CaptureRequest.CONTROL_AWB_MODE) == null || builder.get(CaptureRequest.CONTROL_AWB_MODE) != white_balance ) {
				builder.set(CaptureRequest.CONTROL_AWB_MODE, white_balance);
				return true;
			}
			return false;
		}

		private boolean setAEMode(CaptureRequest.Builder builder, boolean is_still) {
			if( has_iso ) {
				builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
				builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
				builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure_time);
				// set flash via CaptureRequest.FLASH
				if( flash_value.equals("flash_off") ) {
					builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
				}
				else if( flash_value.equals("flash_auto") ) {
					builder.set(CaptureRequest.FLASH_MODE, is_still ? CameraMetadata.FLASH_MODE_SINGLE : CameraMetadata.FLASH_MODE_OFF);
				}
				else if( flash_value.equals("flash_on") ) {
					builder.set(CaptureRequest.FLASH_MODE, is_still ? CameraMetadata.FLASH_MODE_SINGLE : CameraMetadata.FLASH_MODE_OFF);
				}
				else if( flash_value.equals("flash_torch") ) {
					builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
				}
				else if( flash_value.equals("flash_red_eye") ) {
					builder.set(CaptureRequest.FLASH_MODE, is_still ? CameraMetadata.FLASH_MODE_SINGLE : CameraMetadata.FLASH_MODE_OFF);
				}
			}
			else {
				// prefer to set flash via the ae mode (otherwise get even worse results), except for torch which we can't
				if( flash_value.equals("flash_off") ) {
					builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
					builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
				}
				else if( flash_value.equals("flash_auto") ) {
					builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH);
					builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
				}
				else if( flash_value.equals("flash_on") ) {
					builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
					builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
				}
				else if( flash_value.equals("flash_torch") ) {
					builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
					builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
				}
				else if( flash_value.equals("flash_red_eye") ) {
					builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE);
					builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
				}
			}
			return true;
		}

		private void setCropRegion(CaptureRequest.Builder builder) {
			if( scalar_crop_region != null ) {
				builder.set(CaptureRequest.SCALER_CROP_REGION, scalar_crop_region);
			}
		}

		private boolean setExposureCompensation(CaptureRequest.Builder builder) {
			if( !has_ae_exposure_compensation )
				return false;
			if( has_iso ) {
				return false;
			}
			if( builder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) == null || ae_exposure_compensation != builder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) ) {
				builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ae_exposure_compensation);
				return true;
			}
			return false;
		}

		private void setFocusMode(CaptureRequest.Builder builder) {
			if( has_af_mode ) {
				builder.set(CaptureRequest.CONTROL_AF_MODE, af_mode);
			}
		}

		private void setFocusDistance(CaptureRequest.Builder builder) {
			builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus_distance);
		}

		private void setAutoExposureLock(CaptureRequest.Builder builder) {
			builder.set(CaptureRequest.CONTROL_AE_LOCK, ae_lock);
		}

		private void setAFRegions(CaptureRequest.Builder builder) {
			if( af_regions != null && characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) > 0 ) {
				builder.set(CaptureRequest.CONTROL_AF_REGIONS, af_regions);
			}
		}

		private void setAERegions(CaptureRequest.Builder builder) {
			if( ae_regions != null && characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) > 0 ) {
				builder.set(CaptureRequest.CONTROL_AE_REGIONS, ae_regions);
			}
		}

		private void setFaceDetectMode(CaptureRequest.Builder builder) {
			if( has_face_detect_mode )
				builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, face_detect_mode);
		}

		private void setVideoStabilization(CaptureRequest.Builder builder) {
			builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, video_stabilization ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
		}

		// n.b., if we add more methods, remember to update setupBuilder() above!
	}

	private CameraSettings camera_settings = new CameraSettings();
	private boolean push_repeating_request_when_torch_off = false;
	private CaptureRequest push_repeating_request_when_torch_off_id = null;
	private boolean push_set_ae_lock = false;
	private CaptureRequest push_set_ae_lock_id = null;

	private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

		@Override
		public void onOpened(CameraDevice cameraDevice) {
			camera = cameraDevice;
			startPreview();
		}

		@Override
		public void onDisconnected(CameraDevice cameraDevice) {
			cameraDevice.close();
			camera = null;
		}

		@Override
		public void onError(CameraDevice cameraDevice, int error) {
			cameraDevice.close();
			camera = null;
		}

	};

	/**
	 * Starts a background thread and its {@link Handler}.
	 */
	private void startBackgroundThread() {
		mBackgroundThread = new HandlerThread("CameraBackground");
		mBackgroundThread.start();
		mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
	}

	/**
	 * Stops the background thread and its {@link Handler}.
	 */
	private void stopBackgroundThread() {
		mBackgroundThread.quitSafely();
		try {
			mBackgroundThread.join();
			mBackgroundThread = null;
			mBackgroundHandler = null;
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private void openCamera(int width, int height) {
		CameraManager manager = (CameraManager) this.context.getSystemService(Context.CAMERA_SERVICE);
		try {
			String cameraId = manager.getCameraIdList()[cameraID];

			// Choose the sizes for camera preview and video recording
			characteristics = manager.getCameraCharacteristics(cameraId);
			StreamConfigurationMap map = characteristics
					.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
//			mVideoSize = new Size(width, height);
//			mPreviewSize = new Size(width, height);

			mMediaRecorder = new MediaRecorder();
			if (ContextCompat.checkSelfPermission(this.context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
					|| ContextCompat.checkSelfPermission(this.context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
				manager.openCamera(cameraId, mStateCallback, null);
		} catch (CameraAccessException e) {
			Toast.makeText(this.context, "Cannot access the camera.", Toast.LENGTH_SHORT).show();
		} catch (NullPointerException e) {
			// Currently an NPE is thrown when the Camera2API is used but not supported on the
			// device this code runs.
		}
	}

	private void closeCamera() {
		closePreviewSession();
		if (null != camera) {
            camera.close();
            camera = null;
        }
		if (null != mMediaRecorder) {
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
	}

	public void startPreview() {
		if (null == camera || !mTextureView.isAvailable()) {
			return;
		}
		try {
			closePreviewSession();
			SurfaceTexture texture = mTextureView.getSurfaceTexture();
			assert texture != null;
			texture.setDefaultBufferSize(preview_width, preview_height);
			previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

			surface_texture = new Surface(texture);
			previewBuilder.addTarget(surface_texture);

			camera.createCaptureSession(Arrays.asList(surface_texture), new CameraCaptureSession.StateCallback() {

				@Override
				public void onConfigured(CameraCaptureSession cameraCaptureSession) {
					captureSession = cameraCaptureSession;
					updatePreview();
				}

				@Override
				public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
					//
				}
			}, mBackgroundHandler);
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	private void updatePreview() {
		if (null == camera) {
			return;
		}
		try {
			setUpCaptureRequestBuilder(previewBuilder);
			HandlerThread thread = new HandlerThread("CameraPreview");
			thread.start();
			captureSession.setRepeatingRequest(previewBuilder.build(), null, mBackgroundHandler);
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
		//builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
		builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
		previewBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
		builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO);
		previewBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO);
		builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(30, 30));
		previewBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(30, 30));
	}

	private String getVideoFilePath(Context context) {
		return context.getExternalFilesDir(null).getAbsolutePath() + "/"
				+ System.currentTimeMillis() + ".mp4";
	}

	private void closePreviewSession() {
		if (captureSession != null) {
			captureSession.close();
			captureSession = null;
		}
	}

	public CameraController2(AutoFitTextureView textureView, int width, int height, int cameraId) {
		super(cameraId);
		this.mTextureView = textureView;
		this.cameraID = cameraId;
		this.context = textureView.getContext();

		thread = new HandlerThread("CameraBackground");
		thread.start();
		handler = new Handler(thread.getLooper());

		openCamera(width, height);
	}

	@Override
	public void release() {
		if( thread != null ) {
			thread.quitSafely();
			try {
				thread.join();
				thread = null;
				handler = null;
			}
			catch(InterruptedException e) {
				e.printStackTrace();
			}
		}
		if( captureSession != null ) {
			captureSession.close();
			captureSession = null;
		}
		previewBuilder = null;
		if( camera != null ) {
			camera.close();
			camera = null;
		}
		if( imageReader != null ) {
			imageReader.close();
			imageReader = null;
		}
		/*if( previewImageReader != null ) {
			previewImageReader.close();
			previewImageReader = null;
		}*/
	}

	private List<String> convertFocusModesToValues(int [] supported_focus_modes_arr, float minimum_focus_distance) {
		List<Integer> supported_focus_modes = new ArrayList<>();
		for(int i=0;i<supported_focus_modes_arr.length;i++)
			supported_focus_modes.add(supported_focus_modes_arr[i]);
		List<String> output_modes = new Vector<>();
		if( supported_focus_modes != null ) {
			// also resort as well as converting
			if( supported_focus_modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ) {
				output_modes.add("focus_mode_auto");
			}
			if( supported_focus_modes.contains(CaptureRequest.CONTROL_AF_MODE_MACRO) ) {
				output_modes.add("focus_mode_macro");
			}
			if( supported_focus_modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ) {
				output_modes.add("focus_mode_locked");
			}
			if( supported_focus_modes.contains(CaptureRequest.CONTROL_AF_MODE_OFF) ) {
				output_modes.add("focus_mode_infinity");
				if( minimum_focus_distance > 0.0f ) {
					output_modes.add("focus_mode_manual2");
				}
			}
			if( supported_focus_modes.contains(CaptureRequest.CONTROL_AF_MODE_EDOF) ) {
				output_modes.add("focus_mode_edof");
			}
			if( supported_focus_modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ) {
				output_modes.add("focus_mode_continuous_video");
			}
		}
		return output_modes;
	}

	public String getAPI() {
		return "Camera2 (Android L)";
	}

	@Override
	public CameraFeatures getCameraFeatures() {
		CameraFeatures camera_features = new CameraFeatures();

		float max_zoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
		camera_features.is_zoom_supported = max_zoom > 0.0f;

		if( camera_features.is_zoom_supported ) {
			// set 20 steps per 2x factor
			final int steps_per_2x_factor = 20;
			//final double scale_factor = Math.pow(2.0, 1.0/(double)steps_per_2x_factor);
			int n_steps =(int)( (steps_per_2x_factor * Math.log(max_zoom + 1.0e-11)) / Math.log(2.0));
			final double scale_factor = Math.pow(max_zoom, 1.0/(double)n_steps);

			camera_features.zoom_ratios = new ArrayList<Integer>();
			camera_features.zoom_ratios.add(100);
			double zoom = 1.0;
			for(int i=0;i<n_steps-1;i++) {
				zoom *= scale_factor;
				camera_features.zoom_ratios.add((int)(zoom*100));
			}
			camera_features.zoom_ratios.add((int)(max_zoom*100));
			camera_features.max_zoom = camera_features.zoom_ratios.size()-1;
			this.zoom_ratios = camera_features.zoom_ratios;
		}
		else {
			this.zoom_ratios = null;
		}

		int [] face_modes = characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
		camera_features.supports_face_detection = false;
		for(int i=0;i<face_modes.length;i++) {
			// Although we currently only make use of the "SIMPLE" features, some devices (e.g., Nexus 6) support FULL and not SIMPLE.
			// We don't support SIMPLE yet, as I don't have any devices to test this.
			if( face_modes[i] == CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_FULL ) {
				camera_features.supports_face_detection = true;
			}
		}
		if( camera_features.supports_face_detection ) {
			int face_count = characteristics.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT);
			if( face_count <= 0 ) {
				camera_features.supports_face_detection = false;
			}
		}

		StreamConfigurationMap configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

		android.util.Size [] camera_picture_sizes = configs.getOutputSizes(ImageFormat.JPEG);
		camera_features.picture_sizes = new ArrayList<>();
		for(android.util.Size camera_size : camera_picture_sizes) {
			camera_features.picture_sizes.add(new CameraController.Size(camera_size.getWidth(), camera_size.getHeight()));
		}

		android.util.Size [] camera_video_sizes = configs.getOutputSizes(MediaRecorder.class);
		camera_features.video_sizes = new ArrayList<>();
		for(android.util.Size camera_size : camera_video_sizes) {
			if( camera_size.getWidth() > 3840 || camera_size.getHeight() > 2160 )
				continue; // Nexus 6 returns these, even though not supported?!
			camera_features.video_sizes.add(new CameraController.Size(camera_size.getWidth(), camera_size.getHeight()));
		}

		android.util.Size [] camera_preview_sizes = configs.getOutputSizes(SurfaceTexture.class);
		camera_features.preview_sizes = new ArrayList<>();
		Point display_size = new Point();
		Activity activity = (Activity)context;
		{
			Display display = activity.getWindowManager().getDefaultDisplay();
			display.getRealSize(display_size);
		}
		for(android.util.Size camera_size : camera_preview_sizes) {
			if( camera_size.getWidth() > display_size.x || camera_size.getHeight() > display_size.y )
				continue; // Nexus 6 returns these, even though not supported?! (get green corruption lines if we allow these)
			camera_features.preview_sizes.add(new CameraController.Size(camera_size.getWidth(), camera_size.getHeight()));
		}

		if( characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ) {
			camera_features.supported_flash_values = new ArrayList<>();
			camera_features.supported_flash_values.add("flash_off");
			camera_features.supported_flash_values.add("flash_auto");
			camera_features.supported_flash_values.add("flash_on");
			camera_features.supported_flash_values.add("flash_torch");
			camera_features.supported_flash_values.add("flash_red_eye");
		}

		camera_features.minimum_focus_distance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
		int [] supported_focus_modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES); // Android format
		camera_features.supported_focus_values = convertFocusModesToValues(supported_focus_modes, camera_features.minimum_focus_distance); // convert to our format (also resorts)
		camera_features.max_num_focus_areas = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);

		camera_features.is_exposure_lock_supported = true;

		camera_features.is_video_stabilization_supported = true;

		Range<Integer> iso_range = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
		if( iso_range != null ) {
			camera_features.supports_iso_range = true;
			camera_features.min_iso = iso_range.getLower();
			camera_features.max_iso = iso_range.getUpper();
			// we only expose exposure_time if iso_range is supported
			Range<Long> exposure_time_range = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
			if( exposure_time_range != null ) {
				camera_features.supports_exposure_time = true;
				camera_features.min_exposure_time = exposure_time_range.getLower();
				camera_features.max_exposure_time = exposure_time_range.getUpper();
			}
		}

		Range<Integer> exposure_range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
		camera_features.min_exposure = exposure_range.getLower();
		camera_features.max_exposure = exposure_range.getUpper();
		camera_features.exposure_step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP).floatValue();

		camera_features.can_disable_shutter_sound = true;

		return camera_features;
	}

	private String convertSceneMode(int value2) {
		String value = null;
		switch( value2 ) {
			case CameraMetadata.CONTROL_SCENE_MODE_ACTION:
				value = "action";
				break;
			case CameraMetadata.CONTROL_SCENE_MODE_BARCODE:
				value = "barcode";
				break;
			case CameraMetadata.CONTROL_SCENE_MODE_BEACH:
				value = "beach";
				break;
			case CameraMetadata.CONTROL_SCENE_MODE_CANDLELIGHT:
				value = "candlelight";
				break;
			case CameraMetadata.CONTROL_SCENE_MODE_DISABLED:
				value = "auto";
				break;
			case CameraMetadata.CONTROL_SCENE_MODE_FIREWORKS:
				value = "fireworks";
				break;
			// "hdr" no longer available in Camera2
		/*case CameraMetadata.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO:
			// new for Camera2
			value = "high-speed-video";
			break;*/
			case CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE:
				value = "landscape";
				break;
			case CameraMetadata.CONTROL_SCENE_MODE_NIGHT:
				value = "night";
				break;
			case CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT:
				value = "night-portrait";
				break;
			case CameraMetadata.CONTROL_SCENE_MODE_PARTY:
				value = "party";
				break;
			case CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT:
				value = "portrait";
				break;
			case CameraMetadata.CONTROL_SCENE_MODE_SNOW:
				value = "snow";
				break;
			case CameraMetadata.CONTROL_SCENE_MODE_SPORTS:
				value = "sports";
				break;
			case CameraMetadata.CONTROL_SCENE_MODE_STEADYPHOTO:
				value = "steadyphoto";
				break;
			case CameraMetadata.CONTROL_SCENE_MODE_SUNSET:
				value = "sunset";
				break;
			case CameraMetadata.CONTROL_SCENE_MODE_THEATRE:
				value = "theatre";
				break;
			default:
				value = null;
				break;
		}
		return value;
	}

	private void setRepeatingRequest() throws CameraAccessException {
		setRepeatingRequest(previewBuilder.build());
	}

	private void setRepeatingRequest(CaptureRequest request) throws CameraAccessException {
		if( camera == null || captureSession == null ) {
			return;
		}
		captureSession.setRepeatingRequest(request, previewCaptureCallback, null);
	}

	private CameraCaptureSession.CaptureCallback previewCaptureCallback = new CameraCaptureSession.CaptureCallback() {
		public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult partialResult) {
			process(request, partialResult, false);
		}

		public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
			process(request, result, true);
		}

		private void process(CaptureRequest request, CaptureResult result, boolean is_total) {
			/*if( MyDebug.LOG )
				Log.d(TAG, "preview onCaptureCompleted, state: " + state);*/
			/*int af_state = result.get(CaptureResult.CONTROL_AF_STATE);
			if( af_state != CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN ) {
				if( MyDebug.LOG )
					Log.d(TAG, "CONTROL_AF_STATE = " + af_state);
			}*/
			/*if( MyDebug.LOG ) {
				if( autofocus_cb == null ) {
					int af_state = result.get(CaptureResult.CONTROL_AF_STATE);
					if( af_state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED )
						Log.d(TAG, "onCaptureCompleted: autofocus success but no callback set");
					else if( af_state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED )
						Log.d(TAG, "onCaptureCompleted: autofocus failed but no callback set");
				}
			}*/
			if( state == STATE_NORMAL ) {
				// do nothing
			}
			else if( state == STATE_WAITING_AUTOFOCUS ) {
				// check for autofocus completing
				int af_state = result.get(CaptureResult.CONTROL_AF_STATE);
				//Log.d(TAG, "onCaptureCompleted: af_state: " + af_state);
				if( af_state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af_state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ) {
					state = STATE_NORMAL;
					// we need to cancel af trigger, otherwise sometimes things seem to get confused, with the autofocus thinking it's completed too early
			    	/*previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
			    	capture();
			    	previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);*/

					/*if( jpeg_cb != null ) {
						runPrecapture();
					}
					else*/ if( autofocus_cb != null ) {
						autofocus_cb.onAutoFocus(af_state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED);
						autofocus_cb = null;
					}
				}
			}
			else if( state == STATE_WAITING_PRECAPTURE_START ) {
				// CONTROL_AE_STATE can be null on some devices
				Integer ae_state = result.get(CaptureResult.CONTROL_AE_STATE);
				if( ae_state == null || ae_state == CaptureResult.CONTROL_AE_STATE_PRECAPTURE || ae_state == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ) {
					state = STATE_WAITING_PRECAPTURE_DONE;
				}
			}
			else if( state == STATE_WAITING_PRECAPTURE_DONE ) {
				// CONTROL_AE_STATE can be null on some devices
				Integer ae_state = result.get(CaptureResult.CONTROL_AE_STATE);
				if( ae_state == null || ae_state != CaptureResult.CONTROL_AE_STATE_PRECAPTURE ) {
					state = STATE_NORMAL;
//					takePictureAfterPrecapture();
				}
			}

			if( is_total ) {
				if( result.get(CaptureResult.SENSOR_SENSITIVITY) != null ) {
					capture_result_has_iso = true;
					capture_result_iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
					/*if( MyDebug.LOG )
						Log.d(TAG, "capture_result_iso: " + capture_result_iso);*/
					if( camera_settings.has_iso && camera_settings.iso != capture_result_iso ) {
						// ugly hack: problem that when we start recording video (video_recorder.start() call), this often causes the ISO setting to reset to the wrong value!
						// seems to happen more often with shorter exposure time
						// seems to happen on other camera apps with Camera2 API too
						// this workaround still means a brief flash with incorrect ISO, but is best we can do for now!
						try {
							setRepeatingRequest();
						}
						catch(CameraAccessException e) {
							e.printStackTrace();
						}
					}
				}
				else {
					capture_result_has_iso = false;
				}
				if( result.get(CaptureResult.SENSOR_EXPOSURE_TIME) != null ) {
					capture_result_has_exposure_time = true;
					capture_result_exposure_time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
				}
				else {
					capture_result_has_exposure_time = false;
				}
				if( result.get(CaptureResult.SENSOR_FRAME_DURATION) != null ) {
					capture_result_has_frame_duration = true;
					capture_result_frame_duration = result.get(CaptureResult.SENSOR_FRAME_DURATION);
				}
				else {
					capture_result_has_frame_duration = false;
				}
				/*if( MyDebug.LOG ) {
					if( result.get(CaptureResult.SENSOR_EXPOSURE_TIME) != null ) {
						long capture_result_exposure_time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
						Log.d(TAG, "capture_result_exposure_time: " + capture_result_exposure_time);
					}
					if( result.get(CaptureResult.SENSOR_FRAME_DURATION) != null ) {
						long capture_result_frame_duration = result.get(CaptureResult.SENSOR_FRAME_DURATION);
						Log.d(TAG, "capture_result_frame_duration: " + capture_result_frame_duration);
					}
				}*/
			}

			if( face_detection_listener != null && previewBuilder != null && previewBuilder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) != null && previewBuilder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) == CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL ) {
				Rect sensor_rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
				android.hardware.camera2.params.Face [] camera_faces = result.get(CaptureResult.STATISTICS_FACES);
				if( camera_faces != null ) {
					CameraController.Face [] faces = new CameraController.Face[camera_faces.length];
					for(int i=0;i<camera_faces.length;i++) {
						faces[i] = convertFromCameraFace(sensor_rect, camera_faces[i]);
					}
					face_detection_listener.onFaceDetection(faces);
				}
			}

			if( is_total && push_repeating_request_when_torch_off && push_repeating_request_when_torch_off_id == request ) {
				Integer flash_state = result.get(CaptureResult.FLASH_STATE);
				if( flash_state != null && flash_state == CaptureResult.FLASH_STATE_READY ) {
					push_repeating_request_when_torch_off = false;
					push_repeating_request_when_torch_off_id = null;
					try {
						setRepeatingRequest();
					}
					catch(CameraAccessException e) {
						e.printStackTrace();
					}
				}
			}
			if( is_total && push_set_ae_lock && push_set_ae_lock_id == request ) {
				push_set_ae_lock = false;
				push_set_ae_lock_id = null;
				camera_settings.setAutoExposureLock(previewBuilder);
				try {
					setRepeatingRequest();
				}
				catch(CameraAccessException e) {
					e.printStackTrace();
				}
			}
		}
	};

	@Override
	public SupportedValues setSceneMode(String value) {
		// we convert to/from strings to be compatible with original Android Camera API
		String default_value = getDefaultSceneMode();
		int [] values2 = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
		boolean has_disabled = false;
		List<String> values = new ArrayList<String>();
		for(int i=0;i<values2.length;i++) {
			if( values2[i] == CameraMetadata.CONTROL_SCENE_MODE_DISABLED )
				has_disabled = true;
			String this_value = convertSceneMode(values2[i]);
			if( this_value != null ) {
				values.add(this_value);
			}
		}
		if( !has_disabled ) {
			values.add(0, "auto");
		}
		SupportedValues supported_values = checkModeIsSupported(values, value, default_value);
		if( supported_values != null ) {
			int selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_DISABLED;
			if( supported_values.selected_value.equals("action") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_ACTION;
			}
			else if( supported_values.selected_value.equals("barcode") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_BARCODE;
			}
			else if( supported_values.selected_value.equals("beach") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_BEACH;
			}
			else if( supported_values.selected_value.equals("candlelight") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_CANDLELIGHT;
			}
			else if( supported_values.selected_value.equals("auto") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_DISABLED;
			}
			else if( supported_values.selected_value.equals("fireworks") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_FIREWORKS;
			}
			// "hdr" no longer available in Camera2
			else if( supported_values.selected_value.equals("landscape") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE;
			}
			else if( supported_values.selected_value.equals("night") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_NIGHT;
			}
			else if( supported_values.selected_value.equals("night-portrait") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT;
			}
			else if( supported_values.selected_value.equals("party") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_PARTY;
			}
			else if( supported_values.selected_value.equals("portrait") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT;
			}
			else if( supported_values.selected_value.equals("snow") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_SNOW;
			}
			else if( supported_values.selected_value.equals("sports") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_SPORTS;
			}
			else if( supported_values.selected_value.equals("steadyphoto") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_STEADYPHOTO;
			}
			else if( supported_values.selected_value.equals("sunset") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_SUNSET;
			}
			else if( supported_values.selected_value.equals("theatre") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_THEATRE;
			}

			camera_settings.scene_mode = selected_value2;
			if( camera_settings.setSceneMode(previewBuilder) ) {
				try {
					setRepeatingRequest();
				}
				catch(CameraAccessException e) {
					e.printStackTrace();
				}
			}
		}
		return supported_values;
	}

	@Override
	public String getSceneMode() {
		if( previewBuilder.get(CaptureRequest.CONTROL_SCENE_MODE) == null )
			return null;
		int value2 = previewBuilder.get(CaptureRequest.CONTROL_SCENE_MODE);
		String value = convertSceneMode(value2);
		return value;
	}

	private String convertColorEffect(int value2) {
		String value = null;
		switch( value2 ) {
			case CameraMetadata.CONTROL_EFFECT_MODE_AQUA:
				value = "aqua";
				break;
			case CameraMetadata.CONTROL_EFFECT_MODE_BLACKBOARD:
				value = "blackboard";
				break;
			case CameraMetadata.CONTROL_EFFECT_MODE_MONO:
				value = "mono";
				break;
			case CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE:
				value = "negative";
				break;
			case CameraMetadata.CONTROL_EFFECT_MODE_OFF:
				value = "none";
				break;
			case CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE:
				value = "posterize";
				break;
			case CameraMetadata.CONTROL_EFFECT_MODE_SEPIA:
				value = "sepia";
				break;
			case CameraMetadata.CONTROL_EFFECT_MODE_SOLARIZE:
				value = "solarize";
				break;
			case CameraMetadata.CONTROL_EFFECT_MODE_WHITEBOARD:
				value = "whiteboard";
				break;
			default:
				value = null;
				break;
		}
		return value;
	}

	@Override
	public SupportedValues setColorEffect(String value) {
		// we convert to/from strings to be compatible with original Android Camera API
		String default_value = getDefaultColorEffect();
		int [] values2 = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS);
		List<String> values = new ArrayList<String>();
		for(int i=0;i<values2.length;i++) {
			String this_value = convertColorEffect(values2[i]);
			if( this_value != null ) {
				values.add(this_value);
			}
		}
		SupportedValues supported_values = checkModeIsSupported(values, value, default_value);
		if( supported_values != null ) {
			int selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_OFF;
			if( supported_values.selected_value.equals("aqua") ) {
				selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_AQUA;
			}
			else if( supported_values.selected_value.equals("blackboard") ) {
				selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_BLACKBOARD;
			}
			else if( supported_values.selected_value.equals("mono") ) {
				selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_MONO;
			}
			else if( supported_values.selected_value.equals("negative") ) {
				selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE;
			}
			else if( supported_values.selected_value.equals("none") ) {
				selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_OFF;
			}
			else if( supported_values.selected_value.equals("posterize") ) {
				selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE;
			}
			else if( supported_values.selected_value.equals("sepia") ) {
				selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_SEPIA;
			}
			else if( supported_values.selected_value.equals("solarize") ) {
				selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_SOLARIZE;
			}
			else if( supported_values.selected_value.equals("whiteboard") ) {
				selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_WHITEBOARD;
			}

			camera_settings.color_effect = selected_value2;
			if( camera_settings.setColorEffect(previewBuilder) ) {
				try {
					setRepeatingRequest();
				}
				catch(CameraAccessException e) {
					e.printStackTrace();
				}
			}
		}
		return supported_values;
	}

	@Override
	public String getColorEffect() {
		if( previewBuilder.get(CaptureRequest.CONTROL_EFFECT_MODE) == null )
			return null;
		int value2 = previewBuilder.get(CaptureRequest.CONTROL_EFFECT_MODE);
		String value = convertColorEffect(value2);
		return value;
	}

	private String convertWhiteBalance(int value2) {
		String value = null;
		switch( value2 ) {
			case CameraMetadata.CONTROL_AWB_MODE_AUTO:
				value = "auto";
				break;
			case CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT:
				value = "cloudy-daylight";
				break;
			case CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT:
				value = "daylight";
				break;
			case CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT:
				value = "fluorescent";
				break;
			case CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT:
				value = "incandescent";
				break;
			case CameraMetadata.CONTROL_AWB_MODE_SHADE:
				value = "shade";
				break;
			case CameraMetadata.CONTROL_AWB_MODE_TWILIGHT:
				value = "twilight";
				break;
			case CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT:
				value = "warm-fluorescent";
				break;
			default:
				value = null;
				break;
		}
		return value;
	}

	@Override
	public SupportedValues setWhiteBalance(String value) {
		// we convert to/from strings to be compatible with original Android Camera API
		String default_value = getDefaultWhiteBalance();
		int [] values2 = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
		List<String> values = new ArrayList<String>();
		for(int i=0;i<values2.length;i++) {
			String this_value = convertWhiteBalance(values2[i]);
			if( this_value != null ) {
				values.add(this_value);
			}
		}
		SupportedValues supported_values = checkModeIsSupported(values, value, default_value);
		if( supported_values != null ) {
			int selected_value2 = CameraMetadata.CONTROL_AWB_MODE_AUTO;
			if( supported_values.selected_value.equals("auto") ) {
				selected_value2 = CameraMetadata.CONTROL_AWB_MODE_AUTO;
			}
			else if( supported_values.selected_value.equals("cloudy-daylight") ) {
				selected_value2 = CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT;
			}
			else if( supported_values.selected_value.equals("daylight") ) {
				selected_value2 = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT;
			}
			else if( supported_values.selected_value.equals("fluorescent") ) {
				selected_value2 = CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT;
			}
			else if( supported_values.selected_value.equals("incandescent") ) {
				selected_value2 = CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT;
			}
			else if( supported_values.selected_value.equals("shade") ) {
				selected_value2 = CameraMetadata.CONTROL_AWB_MODE_SHADE;
			}
			else if( supported_values.selected_value.equals("twilight") ) {
				selected_value2 = CameraMetadata.CONTROL_AWB_MODE_TWILIGHT;
			}
			else if( supported_values.selected_value.equals("warm-fluorescent") ) {
				selected_value2 = CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT;
			}

			camera_settings.white_balance = selected_value2;
			if( camera_settings.setWhiteBalance(previewBuilder) ) {
				try {
					setRepeatingRequest();
				}
				catch(CameraAccessException e) {
					e.printStackTrace();
				}
			}
		}
		return supported_values;
	}

	@Override
	public String getWhiteBalance() {
		if( previewBuilder.get(CaptureRequest.CONTROL_AWB_MODE) == null )
			return null;
		int value2 = previewBuilder.get(CaptureRequest.CONTROL_AWB_MODE);
		String value = convertWhiteBalance(value2);
		return value;
	}

	@Override
	public SupportedValues setISO(String value) {
		String default_value = getDefaultISO();
		Range<Integer> iso_range = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
		if( iso_range == null ) {
			return null;
		}
		List<String> values = new ArrayList<>();
		values.add(default_value);
		int [] iso_values = {50, 100, 200, 400, 800, 1600, 3200, 6400};
		values.add("" + iso_range.getLower());
		for(int i=0;i<iso_values.length;i++) {
			if( iso_values[i] > iso_range.getLower() && iso_values[i] < iso_range.getUpper() ) {
				values.add("" + iso_values[i]);
			}
		}
		values.add("" + iso_range.getUpper());

		// n.b., we don't use checkModeIsSupported as ISO is a special case with CameraController2: we return a set of ISO values to use in the popup menu, but any ISO within the iso_range is valid
		SupportedValues supported_values = null;
		try {
			if( value.equals(default_value) ) {
				supported_values = new SupportedValues(values, value);
				camera_settings.has_iso = false;
				camera_settings.iso = 0;
				if( camera_settings.setAEMode(previewBuilder, false) ) {
					setRepeatingRequest();
				}
			}
			else {
				try {
					int selected_value2 = Integer.parseInt(value);
					if( selected_value2 < iso_range.getLower() )
						selected_value2 = iso_range.getLower();
					if( selected_value2 > iso_range.getUpper() )
						selected_value2 = iso_range.getUpper();
					supported_values = new SupportedValues(values, "" + selected_value2);
					camera_settings.has_iso = true;
					camera_settings.iso = selected_value2;
					if( camera_settings.setAEMode(previewBuilder, false) ) {
						setRepeatingRequest();
					}
				}
				catch(NumberFormatException exception) {
					supported_values = new SupportedValues(values, default_value);
					camera_settings.has_iso = false;
					camera_settings.iso = 0;
					if( camera_settings.setAEMode(previewBuilder, false) ) {
						setRepeatingRequest();
					}
				}
			}
		}
		catch(CameraAccessException e) {
			e.printStackTrace();
		}

		return supported_values;
	}

	@Override
	public String getISOKey() {
		return "";
	}

	@Override
	public int getISO() {
		return camera_settings.iso;
	}

	@Override
	// Returns whether ISO was modified
	// N.B., use setISO(String) to switch between auto and manual mode
	public boolean setISO(int iso) {
		if( camera_settings.iso == iso ) {
			return false;
		}
		try {
			camera_settings.iso = iso;
			if( camera_settings.setAEMode(previewBuilder, false) ) {
				setRepeatingRequest();
			}
		}
		catch(CameraAccessException e) {
			e.printStackTrace();
		}
		return true;
	}

	@Override
	public long getExposureTime() {
		return camera_settings.exposure_time;
	}

	@Override
	// Returns whether exposure time was modified
	// N.B., use setISO(String) to switch between auto and manual mode
	public boolean setExposureTime(long exposure_time) {
		if( camera_settings.exposure_time == exposure_time ) {
			return false;
		}
		try {
			camera_settings.exposure_time = exposure_time;
			if( camera_settings.setAEMode(previewBuilder, false) ) {
				setRepeatingRequest();
			}
		}
		catch(CameraAccessException e) {
			e.printStackTrace();
		}
		return true;
	}

	@Override
	public Size getPictureSize() {
		Size size = new Size(picture_width, picture_height);
		return size;
	}

	@Override
	public void setPictureSize(int width, int height) {
		if( camera == null ) {
			return;
		}
		if( captureSession != null ) {
			// can only call this when captureSession not created - as the surface of the imageReader we create has to match the surface we pass to the captureSession
			throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
		}
		this.picture_width = width;
		this.picture_height = height;
	}

	private void createPictureImageReader() {
		if( captureSession != null ) {
			// can only call this when captureSession not created - as the surface of the imageReader we create has to match the surface we pass to the captureSession
			throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
		}
		if( imageReader != null ) {
			imageReader.close();
		}
		if( picture_width == 0 || picture_height == 0 ) {
			throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
		}
		imageReader = ImageReader.newInstance(picture_width, picture_height, ImageFormat.JPEG, 2);
		imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
			@Override
			public void onImageAvailable(ImageReader reader) {
				if( jpeg_cb == null ) {
					return;
				}
				Image image = reader.acquireNextImage();
				ByteBuffer buffer = image.getPlanes()[0].getBuffer();
				byte [] bytes = new byte[buffer.remaining()];
				buffer.get(bytes);
				image.close();
				image = null;
				// need to set jpeg_cb etc to null before calling onPictureTaken, as that may reenter CameraController to take another photo (if in burst mode) - see testTakePhotoBurst()
				PictureCallback cb = jpeg_cb;
				jpeg_cb = null;
				take_picture_error_cb = null;
				cb.onPictureTaken(bytes);
			}
		}, null);
	}

	@Override
	public Size getPreviewSize() {
		return new Size(preview_width, preview_height);
	}

	@Override
	public void setPreviewSize(int width, int height) {
		/*if( texture != null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "set size of preview texture");
			texture.setDefaultBufferSize(width, height);
		}*/
		preview_width = width;
		preview_height = height;
		/*if( previewImageReader != null ) {
			previewImageReader.close();
		}
		previewImageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
		*/
	}

	@Override
	public void setVideoStabilization(boolean enabled) {
		camera_settings.video_stabilization = enabled;
		camera_settings.setVideoStabilization(previewBuilder);
		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			e.printStackTrace();
		}
	}

	@Override
	public boolean getVideoStabilization() {
		return camera_settings.video_stabilization;
	}

	@Override
	public int getJpegQuality() {
		return this.camera_settings.jpeg_quality;
	}

	@Override
	public void setJpegQuality(int quality) {
		if( quality < 0 || quality > 100 ) {
			throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
		}
		this.camera_settings.jpeg_quality = (byte)quality;
	}

	@Override
	public int getZoom() {
		return this.current_zoom_value;
	}

	@Override
	public void setZoom(int value) {
		if( zoom_ratios == null ) {
			return;
		}
		if( value < 0 || value > zoom_ratios.size() ) {
			throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
		}
		float zoom = zoom_ratios.get(value)/100.0f;
		Rect sensor_rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
		int left = sensor_rect.width()/2;
		int right = left;
		int top = sensor_rect.height()/2;
		int bottom = top;
		int hwidth = (int)(sensor_rect.width() / (2.0*zoom));
		int hheight = (int)(sensor_rect.height() / (2.0*zoom));
		left -= hwidth;
		right += hwidth;
		top -= hheight;
		bottom += hheight;
		camera_settings.scalar_crop_region = new Rect(left, top, right, bottom);
		camera_settings.setCropRegion(previewBuilder);
		this.current_zoom_value = value;
		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			e.printStackTrace();
		}
	}

	@Override
	public int getExposureCompensation() {
		if( previewBuilder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) == null )
			return 0;
		return previewBuilder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION);
	}

	@Override
	// Returns whether exposure was modified
	public boolean setExposureCompensation(int new_exposure) {
		camera_settings.has_ae_exposure_compensation = true;
		camera_settings.ae_exposure_compensation = new_exposure;
		if( camera_settings.setExposureCompensation(previewBuilder) ) {
			try {
				setRepeatingRequest();
			}
			catch(CameraAccessException e) {
				e.printStackTrace();
			}
			return true;
		}
		return false;
	}

	@Override
	public void setPreviewFpsRange(int min, int max) {
		// TODO Auto-generated method stub

	}

	@Override
	public List<int[]> getSupportedPreviewFpsRange() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	// note, responsibility of callers to check that this is within the valid min/max range
	public long getDefaultExposureTime() {
		return 1000000000l/30;
	}

	@Override
	public void setFocusValue(String focus_value) {
		int focus_mode = CaptureRequest.CONTROL_AF_MODE_AUTO;

		if( focus_value.equals("focus_mode_auto") || focus_value.equals("focus_mode_locked") ) {
			focus_mode = CaptureRequest.CONTROL_AF_MODE_AUTO;
		}
		else if( focus_value.equals("focus_mode_infinity") ) {
			focus_mode = CaptureRequest.CONTROL_AF_MODE_OFF;
			camera_settings.focus_distance = 0.0f;
		}
		else if( focus_value.equals("focus_mode_manual2") ) {
			focus_mode = CaptureRequest.CONTROL_AF_MODE_OFF;
			camera_settings.focus_distance = camera_settings.focus_distance_manual;
		}
		else if( focus_value.equals("focus_mode_macro") ) {
			focus_mode = CaptureRequest.CONTROL_AF_MODE_MACRO;
		}
		else if( focus_value.equals("focus_mode_edof") ) {
			focus_mode = CaptureRequest.CONTROL_AF_MODE_EDOF;
		}
		else if( focus_value.equals("focus_mode_continuous_video") ) {
			focus_mode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
		}
		else {
			return;
		}
		camera_settings.has_af_mode = true;
		camera_settings.af_mode = focus_mode;
		camera_settings.setFocusMode(previewBuilder);
		camera_settings.setFocusDistance(previewBuilder); // also need to set distance, in case changed between infinity, manual or other modes
		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			e.printStackTrace();
		}
	}

	private String convertFocusModeToValue(int focus_mode) {
		String focus_value = "";
		if( focus_mode == CaptureRequest.CONTROL_AF_MODE_AUTO ) {
			focus_value = "focus_mode_auto";
		}
		else if( focus_mode == CaptureRequest.CONTROL_AF_MODE_MACRO ) {
			focus_value = "focus_mode_macro";
		}
		else if( focus_mode == CaptureRequest.CONTROL_AF_MODE_EDOF ) {
			focus_value = "focus_mode_edof";
		}
		else if( focus_mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO ) {
			focus_value = "focus_mode_continuous_video";
		}
		else if( focus_mode == CaptureRequest.CONTROL_AF_MODE_OFF ) {
			focus_value = "focus_mode_manual2"; // n.b., could be infinity
		}
		return focus_value;
	}

	@Override
	public String getFocusValue() {
		int focus_mode = previewBuilder.get(CaptureRequest.CONTROL_AF_MODE) != null ?
				previewBuilder.get(CaptureRequest.CONTROL_AF_MODE) : CaptureRequest.CONTROL_AF_MODE_AUTO;
		return convertFocusModeToValue(focus_mode);
	}

	@Override
	public float getFocusDistance() {
		return camera_settings.focus_distance;
	}

	@Override
	public boolean setFocusDistance(float focus_distance) {
		if( camera_settings.focus_distance == focus_distance ) {
			return false;
		}
		camera_settings.focus_distance = focus_distance;
		camera_settings.focus_distance_manual = focus_distance;
		camera_settings.setFocusDistance(previewBuilder);
		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			e.printStackTrace();
		}
		return true;
	}

	@Override
	public void setFlashValue(String flash_value) {
		if( !characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ) {
			return;
		} else if( camera_settings.flash_value.equals(flash_value) ) {
			return;
		}

		try {
			if( camera_settings.flash_value.equals("flash_torch") ) {
				// hack - first need to turn torch off, otherwise torch remains on (at least on Nexus 6)
				camera_settings.flash_value = "flash_off";
				camera_settings.setAEMode(previewBuilder, false);
				CaptureRequest request = previewBuilder.build();

				// need to wait until torch actually turned off
				camera_settings.flash_value = flash_value;
				camera_settings.setAEMode(previewBuilder, false);
				push_repeating_request_when_torch_off = true;
				push_repeating_request_when_torch_off_id = request;

				setRepeatingRequest(request);
			}
			else {
				camera_settings.flash_value = flash_value;
				if( camera_settings.setAEMode(previewBuilder, false) ) {
					setRepeatingRequest();
				}
			}
		}
		catch(CameraAccessException e) {
			e.printStackTrace();
		}
	}

	@Override
	public String getFlashValue() {
		// returns "" if flash isn't supported
		if( !characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ) {
			return "";
		}
		return camera_settings.flash_value;
	}

	@Override
	public void setRecordingHint(boolean hint) {
		// not relevant for CameraController2
	}

	@Override
	public void setAutoExposureLock(boolean enabled) {
		camera_settings.ae_lock = enabled;
		camera_settings.setAutoExposureLock(previewBuilder);
		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			e.printStackTrace();
		}
	}

	@Override
	public boolean getAutoExposureLock() {
		if( previewBuilder.get(CaptureRequest.CONTROL_AE_LOCK) == null )
			return false;
		return previewBuilder.get(CaptureRequest.CONTROL_AE_LOCK);
	}

	@Override
	public void setRotation(int rotation) {
		this.camera_settings.rotation = rotation;
	}

	@Override
	public void setLocationInfo(Location location) {
		this.camera_settings.location = location;
	}

	@Override
	public void removeLocationInfo() {
		this.camera_settings.location = null;
	}

	@Override
	public void enableShutterSound(boolean enabled) {
		this.sounds_enabled = enabled;
	}

	private Rect convertRectToCamera2(Rect sensor_rect, Rect rect) {
		// CameraController.Area is always [-1000, -1000] to [1000, 1000]
		// but for CameraController2, we must convert to [0, 0] to [sensor width-1, sensor height-1] for use as a MeteringRectangle
		double left_f = (rect.left+1000)/2000.0;
		double top_f = (rect.top+1000)/2000.0;
		double right_f = (rect.right+1000)/2000.0;
		double bottom_f = (rect.bottom+1000)/2000.0;
		int left = (int)(left_f * (sensor_rect.width()-1));
		int right = (int)(right_f * (sensor_rect.width()-1));
		int top = (int)(top_f * (sensor_rect.height()-1));
		int bottom = (int)(bottom_f * (sensor_rect.height()-1));
		left = Math.max(left, 0);
		right = Math.max(right, 0);
		top = Math.max(top, 0);
		bottom = Math.max(bottom, 0);
		left = Math.min(left, sensor_rect.width()-1);
		right = Math.min(right, sensor_rect.width()-1);
		top = Math.min(top, sensor_rect.height()-1);
		bottom = Math.min(bottom, sensor_rect.height()-1);

		Rect camera2_rect = new Rect(left, top, right, bottom);
		return camera2_rect;
	}

	private MeteringRectangle convertAreaToMeteringRectangle(Rect sensor_rect, Area area) {
		Rect camera2_rect = convertRectToCamera2(sensor_rect, area.rect);
		MeteringRectangle metering_rectangle = new MeteringRectangle(camera2_rect, area.weight);
		return metering_rectangle;
	}

	private Rect convertRectFromCamera2(Rect sensor_rect, Rect camera2_rect) {
		// inverse of convertRectToCamera2()
		double left_f = camera2_rect.left/(double)(sensor_rect.width()-1);
		double top_f = camera2_rect.top/(double)(sensor_rect.height()-1);
		double right_f = camera2_rect.right/(double)(sensor_rect.width()-1);
		double bottom_f = camera2_rect.bottom/(double)(sensor_rect.height()-1);
		int left = (int)(left_f * 2000) - 1000;
		int right = (int)(right_f * 2000) - 1000;
		int top = (int)(top_f * 2000) - 1000;
		int bottom = (int)(bottom_f * 2000) - 1000;

		left = Math.max(left, -1000);
		right = Math.max(right, -1000);
		top = Math.max(top, -1000);
		bottom = Math.max(bottom, -1000);
		left = Math.min(left, 1000);
		right = Math.min(right, 1000);
		top = Math.min(top, 1000);
		bottom = Math.min(bottom, 1000);

		Rect rect = new Rect(left, top, right, bottom);
		return rect;
	}

	private Area convertMeteringRectangleToArea(Rect sensor_rect, MeteringRectangle metering_rectangle) {
		Rect area_rect = convertRectFromCamera2(sensor_rect, metering_rectangle.getRect());
		Area area = new Area(area_rect, metering_rectangle.getMeteringWeight());
		return area;
	}

	private CameraController.Face convertFromCameraFace(Rect sensor_rect, android.hardware.camera2.params.Face camera2_face) {
		Rect area_rect = convertRectFromCamera2(sensor_rect, camera2_face.getBounds());
		CameraController.Face face = new CameraController.Face(camera2_face.getScore(), area_rect);
		return face;
	}

	@Override
	public boolean setFocusAndMeteringArea(List<Area> areas) {
		Rect sensor_rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
		boolean has_focus = false;
		boolean has_metering = false;
		if( characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) > 0 ) {
			has_focus = true;
			camera_settings.af_regions = new MeteringRectangle[areas.size()];
			int i = 0;
			for(CameraController.Area area : areas) {
				camera_settings.af_regions[i++] = convertAreaToMeteringRectangle(sensor_rect, area);
			}
			camera_settings.setAFRegions(previewBuilder);
		}
		else
			camera_settings.af_regions = null;
		if( characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) > 0 ) {
			has_metering = true;
			camera_settings.ae_regions = new MeteringRectangle[areas.size()];
			int i = 0;
			for(CameraController.Area area : areas) {
				camera_settings.ae_regions[i++] = convertAreaToMeteringRectangle(sensor_rect, area);
			}
			camera_settings.setAERegions(previewBuilder);
		}
		else
			camera_settings.ae_regions = null;
		if( has_focus || has_metering ) {
			try {
				setRepeatingRequest();
			}
			catch(CameraAccessException e) {
				e.printStackTrace();
			}
		}
		return has_focus;
	}

	@Override
	public void clearFocusAndMetering() {
		Rect sensor_rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
		boolean has_focus = false;
		boolean has_metering = false;
		if( characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) > 0 ) {
			has_focus = true;
			camera_settings.af_regions = new MeteringRectangle[1];
			camera_settings.af_regions[0] = new MeteringRectangle(0, 0, sensor_rect.width()-1, sensor_rect.height()-1, 0);
			camera_settings.setAFRegions(previewBuilder);
		}
		else
			camera_settings.af_regions = null;
		if( characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) > 0 ) {
			has_metering = true;
			camera_settings.ae_regions = new MeteringRectangle[1];
			camera_settings.ae_regions[0] = new MeteringRectangle(0, 0, sensor_rect.width()-1, sensor_rect.height()-1, 0);
			camera_settings.setAERegions(previewBuilder);
		}
		else
			camera_settings.ae_regions = null;
		if( has_focus || has_metering ) {
			try {
				setRepeatingRequest();
			}
			catch(CameraAccessException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public List<Area> getFocusAreas() {
		if( characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) == 0 )
			return null;
		MeteringRectangle [] metering_rectangles = previewBuilder.get(CaptureRequest.CONTROL_AF_REGIONS);
		if( metering_rectangles == null )
			return null;
		Rect sensor_rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
		camera_settings.af_regions[0] = new MeteringRectangle(0, 0, sensor_rect.width()-1, sensor_rect.height()-1, 0);
		if( metering_rectangles.length == 1 && metering_rectangles[0].getRect().left == 0 && metering_rectangles[0].getRect().top == 0 && metering_rectangles[0].getRect().right == sensor_rect.width()-1 && metering_rectangles[0].getRect().bottom == sensor_rect.height()-1 ) {
			// for compatibility with CameraController1
			return null;
		}
		List<Area> areas = new ArrayList<>();
		for(int i=0;i<metering_rectangles.length;i++) {
			areas.add(convertMeteringRectangleToArea(sensor_rect, metering_rectangles[i]));
		}
		return areas;
	}

	@Override
	public List<Area> getMeteringAreas() {
		if( characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) == 0 )
			return null;
		MeteringRectangle [] metering_rectangles = previewBuilder.get(CaptureRequest.CONTROL_AE_REGIONS);
		if( metering_rectangles == null )
			return null;
		Rect sensor_rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
		if( metering_rectangles.length == 1 && metering_rectangles[0].getRect().left == 0 && metering_rectangles[0].getRect().top == 0 && metering_rectangles[0].getRect().right == sensor_rect.width()-1 && metering_rectangles[0].getRect().bottom == sensor_rect.height()-1 ) {
			// for compatibility with CameraController1
			return null;
		}
		List<Area> areas = new ArrayList<>();
		for(int i=0;i<metering_rectangles.length;i++) {
			areas.add(convertMeteringRectangleToArea(sensor_rect, metering_rectangles[i]));
		}
		return areas;
	}

	@Override
	public boolean supportsAutoFocus() {
		if( previewBuilder.get(CaptureRequest.CONTROL_AF_MODE) == null )
			return true;
		int focus_mode = previewBuilder.get(CaptureRequest.CONTROL_AF_MODE);
		if( focus_mode == CaptureRequest.CONTROL_AF_MODE_AUTO || focus_mode == CaptureRequest.CONTROL_AF_MODE_MACRO )
			return true;
		return false;
	}

	@Override
	public boolean focusIsVideo() {
		if( previewBuilder.get(CaptureRequest.CONTROL_AF_MODE) == null )
			return false;
		int focus_mode = previewBuilder.get(CaptureRequest.CONTROL_AF_MODE);
		if( focus_mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO ) {
			return true;
		}
		return false;
	}

	@Override
	public void setPreviewDisplay(SurfaceHolder holder) {
		throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
	}

	@Override
	public void setPreviewTexture(SurfaceTexture texture) {
		if( this.texture != null ) {
			throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
		}
		this.texture = texture;
	}

	private void capture() throws CameraAccessException {
		capture(previewBuilder.build());
	}

	private void capture(CaptureRequest request) throws CameraAccessException {
		if( camera == null || captureSession == null ) {
			return;
		}
		captureSession.capture(request, previewCaptureCallback, null);
	}

	private void createPreviewRequest() {
		if( camera == null  ) {
			return;
		}
		try {
			previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
			camera_settings.setupBuilder(previewBuilder, false);
		}
		catch(CameraAccessException e) {
			//captureSession = null;
			e.printStackTrace();
		}
	}

	private Surface getPreviewSurface() {
		return surface_texture;
	}

	@Override
	public void stopPreview() {
		if( camera == null || captureSession == null ) {
			return;
		}
		try {
			captureSession.stopRepeating();
			captureSession.close();
			captureSession = null;
		}
		catch(CameraAccessException e) {
			e.printStackTrace();
		}
	}

	@Override
	public boolean startFaceDetection() {
		if( previewBuilder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) != null && previewBuilder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) == CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL ) {
			return false;
		}
		camera_settings.has_face_detect_mode = true;
		camera_settings.face_detect_mode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL;
		camera_settings.setFaceDetectMode(previewBuilder);
		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			e.printStackTrace();
		}
		return true;
	}

	@Override
	public void setFaceDetectionListener(final FaceDetectionListener listener) {
		this.face_detection_listener = listener;
	}

	@Override
	public void autoFocus(final AutoFocusCallback cb) {
		if( camera == null || captureSession == null ) {
			// should call the callback, so the application isn't left waiting (e.g., when we autofocus before trying to take a photo)
			cb.onAutoFocus(false);
			return;
		}
		/*if( state == STATE_WAITING_AUTOFOCUS ) {
			if( MyDebug.LOG )
				Log.d(TAG, "already waiting for an autofocus");
			// need to update the callback!
			this.autofocus_cb = cb;
			return;
		}*/
		previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
    	/*if( focus_areas != null ) {
        	previewBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, focus_areas);
    	}
    	if( metering_areas != null ) {
        	previewBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, metering_areas);
    	}*/
		//previewBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
    	/*previewBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f);
		if( MyDebug.LOG ) {
			Float focus_distance = previewBuilder.get(CaptureRequest.LENS_FOCUS_DISTANCE);
			Log.d(TAG, "focus_distance: " + focus_distance);
		}*/
		state = STATE_WAITING_AUTOFOCUS;
		this.autofocus_cb = cb;
		// Camera2Basic sets a repeating request rather than capture, for doing autofocus (and if we do a capture(), sometimes have problem that autofocus never returns)
		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			e.printStackTrace();
			state = STATE_NORMAL;
			autofocus_cb.onAutoFocus(false);
			autofocus_cb = null;
		}
		previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
	}

	@Override
	public void cancelAutoFocus() {
		if( camera == null || captureSession == null ) {
			return;
		}
		previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
		// Camera2Basic does a capture then sets a repeating request - do the same here just to be safe
		try {
			capture();
		}
		catch(CameraAccessException e) {
			e.printStackTrace();
		}
		previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
		this.autofocus_cb = null;
		state = STATE_NORMAL;
		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			e.printStackTrace();
		}
	}

	private void takePictureAfterPrecapture() {
		if( camera == null || captureSession == null ) {
			return;
		}
		try {
			CaptureRequest.Builder stillBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
			camera_settings.setupBuilder(stillBuilder, true);
			stillBuilder.addTarget(imageReader.getSurface());

			CameraCaptureSession.CaptureCallback stillCaptureCallback = new CameraCaptureSession.CaptureCallback() {
				public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
					if( sounds_enabled )
						media_action_sound.play(MediaActionSound.SHUTTER_CLICK);
				}

				public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
					// actual parsing of image data is done in the imageReader's OnImageAvailableListener()
					// need to cancel the autofocus, and restart the preview after taking the photo
					previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
					camera_settings.setAEMode(previewBuilder, false); // not sure if needed, but the AE mode is set again in Camera2Basic
					// n.b., if capture/setRepeatingRequest throw exception, we don't call the take_picture_error_cb.onError() callback, as the photo should have been taken by this point
					try {
						if( !camera_settings.ae_lock && camera_settings.flash_value.equals("flash_on") ) {
							// hack - needed to fix bug on Nexus 6 where auto-exposure sometimes locks when taking a photo of bright scene with flash on!
							// this doesn't completely resolve the issue, but seems to make it far less common; also when it does happen, taking another photo usually fixes it
							previewBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true);
							push_set_ae_lock = true;
							push_set_ae_lock_id = previewBuilder.build();
							capture(push_set_ae_lock_id);
						}
						else {
							capture();
						}
					}
					catch(CameraAccessException e) {
						e.printStackTrace();
					}
					try {
						setRepeatingRequest();
					}
					catch(CameraAccessException e) {
						e.printStackTrace();
						preview_error_cb.onError();
					}
				}
			};
			captureSession.stopRepeating(); // need to stop preview before capture (as done in Camera2Basic; otherwise we get bugs such as flash remaining on after taking a photo with flash)
			captureSession.capture(stillBuilder.build(), stillCaptureCallback, null);
		}
		catch(CameraAccessException e) {
			e.printStackTrace();
			jpeg_cb = null;
			if( take_picture_error_cb != null ) {
				take_picture_error_cb.onError();
				take_picture_error_cb = null;
				return;
			}
		}
	}

	private void runPrecapture() {
		/*takePictureAfterPrecapture();
		if( true )
			return;*/
		// first run precapture sequence
		// use a separate builder for precapture - otherwise have problem that if we take photo with flash auto/on of dark scene, then point to a bright scene, the autoexposure isn't running until we autofocus again
    	/*previewBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
    	state = STATE_WAITING_PRECAPTURE_START;
    	capture();*/
		try {
			CaptureRequest.Builder precaptureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
			camera_settings.setupBuilder(precaptureBuilder, false);
			precaptureBuilder.addTarget(getPreviewSurface());
			precaptureBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
			state = STATE_WAITING_PRECAPTURE_START;
			captureSession.capture(precaptureBuilder.build(), previewCaptureCallback, null);
		}
		catch(CameraAccessException e) {
			e.printStackTrace();
			jpeg_cb = null;
			if( take_picture_error_cb != null ) {
				take_picture_error_cb.onError();
				take_picture_error_cb = null;
				return;
			}
		}
	}

	@Override
	public void takePicture(final PictureCallback raw, final PictureCallback jpeg, final ErrorCallback error) {
		if( camera == null || captureSession == null ) {
			error.onError();
			return;
		}
		this.jpeg_cb = jpeg;
		this.take_picture_error_cb = error;
		if( camera_settings.has_iso ) {
			takePictureAfterPrecapture();
		}
		else {
			runPrecapture();
		}

		/*camera_settings.setupBuilder(previewBuilder, false);
    	previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
		state = STATE_WAITING_AUTOFOCUS;
    	//capture();
    	setRepeatingRequest();*/
	}

	@Override
	public void setDisplayOrientation(int degrees) {
		// for CameraController2, the preview display orientation is handled via the TextureView's transform
		throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
	}

	@Override
	public int getDisplayOrientation() {
		throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
	}

	@Override
	public int getCameraOrientation() {
		return characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
	}

	@Override
	public boolean isFrontFacing() {
		return characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT;
	}

	@Override
	public void unlock() {
		// do nothing at this stage
	}

	@Override
	public void initVideoRecorderPrePrepare(MediaRecorder video_recorder) {
		// do nothing at this stage
	}

	@Override
	public void initVideoRecorderPostPrepare(MediaRecorder video_recorder) throws CameraControllerException {
		try {
			previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
			camera_settings.setupBuilder(previewBuilder, false);
			createCaptureSession(video_recorder);
		}
		catch(CameraAccessException e) {
			e.printStackTrace();
			throw new CameraControllerException();
		}
	}

	@Override
	public void reconnect() {
		createPreviewRequest();
		try {
			createCaptureSession(null);
		} catch (CameraControllerException e) {
			e.printStackTrace();
		}
		/*if( MyDebug.LOG )
			Log.d(TAG, "add preview surface to previewBuilder");
    	Surface surface = getPreviewSurface();
		previewBuilder.addTarget(surface);*/
		//setRepeatingRequest();
	}

	@Override
	public String getParametersString() {
		return null;
	}

	@Override
	public boolean captureResultHasIso() {
		return capture_result_has_iso;
	}

	@Override
	public int captureResultIso() {
		return capture_result_iso;
	}

	@Override
	public boolean captureResultHasExposureTime() {
		return capture_result_has_exposure_time;
	}

	@Override
	public long captureResultExposureTime() {
		return capture_result_exposure_time;
	}

	@Override
	public boolean captureResultHasFrameDuration() {
		return capture_result_has_frame_duration;
	}

	@Override
	public long captureResultFrameDuration() {
		return capture_result_frame_duration;
	}

	private void createCaptureSession(final MediaRecorder video_recorder) throws CameraControllerException {
		if( previewBuilder == null ) {
			throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
		}

		if( captureSession != null ) {
			captureSession.close();
			captureSession = null;
		}

		try {
			captureSession = null;

			if( video_recorder != null ) {
				if( imageReader != null ) {
					imageReader.close();
					imageReader = null;
				}
			}
			else {
				// in some cases need to recreate picture imageReader and the texture default buffer size (e.g., see test testTakePhotoPreviewPaused())
				createPictureImageReader();
			}
			if( texture != null ) {
				// need to set the texture size
				if( preview_width == 0 || preview_height == 0 ) {
					throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
				}
				texture.setDefaultBufferSize(preview_width, preview_height);
				// also need to create a new surface for the texture, in case the size has changed - but make sure we remove the old one first!
				if( surface_texture != null ) {
					previewBuilder.removeTarget(surface_texture);
				}
				this.surface_texture = new Surface(texture);
			}
			/*if( MyDebug.LOG )
			Log.d(TAG, "preview size: " + previewImageReader.getWidth() + " x " + previewImageReader.getHeight());*/

			class MyStateCallback extends CameraCaptureSession.StateCallback {
				boolean callback_done = false;
				@Override
				public void onConfigured(CameraCaptureSession session) {
					if( camera == null ) {
						callback_done = true;
						return;
					}
					captureSession = session;
					Surface surface = getPreviewSurface();
					previewBuilder.addTarget(surface);
					if( video_recorder != null )
						previewBuilder.addTarget(video_recorder.getSurface());
					try {
						setRepeatingRequest();
					}
					catch(CameraAccessException e) {
						e.printStackTrace();
						preview_error_cb.onError();
					}
					callback_done = true;
				}

				@Override
				public void onConfigureFailed(CameraCaptureSession session) {
					callback_done = true;
					// don't throw CameraControllerException here, as won't be caught - instead we throw CameraControllerException below
				}
			}
			MyStateCallback myStateCallback = new MyStateCallback();

			Surface preview_surface = getPreviewSurface();
			Surface capture_surface = video_recorder != null ? video_recorder.getSurface() : imageReader.getSurface();
			camera.createCaptureSession(Arrays.asList(preview_surface/*, previewImageReader.getSurface()*/, capture_surface),
					myStateCallback,
					handler);
			if( captureSession == null ) {
				throw new CameraControllerException();
			}
		}
		catch(CameraAccessException e) {
			e.printStackTrace();
			throw new CameraControllerException();
		}
	}

}
