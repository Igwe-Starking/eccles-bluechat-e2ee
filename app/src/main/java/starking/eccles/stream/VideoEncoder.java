package starking.eccles.stream;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import starking.eccles.bluechat.activities.VideoActivity;

public class VideoEncoder {

	public interface Callback {

		void onConfig(byte[] csd);
		void onFrame(byte[] data,boolean keyFrame);
		/** See {@link AudioEncoder.Callback#onError()} for why this
		 *  exists; same threading caveat applies (codec callback thread, not UI thread). Fired
		 *  only for the video codec itself failing after {@link #start} has completed
		 *  successfully - camera-level setup failures are still reported solely via
		 *  {@link #start}'s boolean return, since those can only occur before a call is fully
		 *  established, a case {@link VideoActivity}
		 *  already handles via that return value. */
		void onError();
	}

	// Camera2 stream configs are always advertised in the sensor's native (landscape) width x
	// height, wide > tall - this pair can never be requested "tall" (e.g. 144x176) because that
	// exact size simply isn't in the device's supported-output-sizes list, so the capture
	// session would fail to configure. The portrait look the user sees on screen comes entirely
	// from rotating the *display* of these landscape frames (see the caller_video rotator in
	// activity_video.xml / VideoActivity#addContent), not from changing this capture size.
	// 176x144 (QCIF) is a long-standing low-bandwidth video-call standard and is close to
	// universally supported, unlike the previous 320x240 (4x the pixels).
	// Raised from 176x144 for a sharper picture. 320x240 (QVGA) is a standard size every camera
	// advertises for MediaCodec surfaces (odd in-between sizes risk the capture session failing to
	// configure). If it lags on a device, step back down to 176x144 / 64_000 / 10 below.
	public static final int WIDTH= 320;
	public static final int HEIGHT= 240;

	// Scaled down together with the resolution/framerate drop above so classic Bluetooth SPP
	// (realistically far below its nominal ~2-3Mbps once encryption/framing overhead and the
	// concurrent audio stream are accounted for) isn't saturated: old config pushed roughly
	// 320*240*15fps at 300kbps; this is about a fifth of the raw pixel throughput, so the
	// bitrate is cut proportionally rather than left disproportionately high for the new size.
	public static final int BITRATE= 140_000;
	public static final int FRAMERATE= 12;

	private CameraDevice cameraDevice;
	private CameraCaptureSession session;
	private MediaCodec encoder;
	private Surface encoderInputSurface;
	private HandlerThread cameraThread;
	private Handler cameraHandler;
	private Callback callback;
	private final Context context;
	private String cameraId;
	private int baseSensor= -1;
	private java.util.function.IntConsumer orientationListener;

	/** Called with the extra rotation (degrees, 0/90/180/270) the viewer must apply, relative to
	 *  the camera the call started on, whenever the active camera changes. */
	public void setOrientationListener(java.util.function.IntConsumer l){ orientationListener= l; }

	private void reportOrientation(CameraManager manager){
		try{
			Integer so= manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION);
			if(so == null) return;
			if(baseSensor < 0){ baseSensor= so; return; }
			int delta= ((so - baseSensor) % 360 + 360) % 360;
			if(orientationListener != null) orientationListener.accept(delta);
		} catch(Exception e){ Log.w("Eccles","orientation lookup failed",e); }
	}
	private Surface localPreviewSurface;

	public VideoEncoder(Context c){
		this.context= c;
	}

	@SuppressLint("MissingPermission")
	public boolean start(Surface localPreviewSurface,Callback cb){
		this.callback= cb;
		this.localPreviewSurface= localPreviewSurface;
		try{
			cameraThread= new HandlerThread("VideoEncoderCamera");
			cameraThread.start();
			cameraHandler= new Handler(cameraThread.getLooper());

			CameraManager manager= (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
			cameraId= pickCamera(manager);
			if(cameraId == null) return false;
			reportOrientation(manager);
			if(!setupEncoder()) return false;

			final boolean[] ok= {false};
			final Semaphore sem= new Semaphore(0);

			manager.openCamera(cameraId,new CameraDevice.StateCallback(){
				@Override
				public void onOpened(CameraDevice cam){
					cameraDevice= cam;
					try{
						List<Surface> targets= new ArrayList<>();
						targets.add(encoderInputSurface);
						if(localPreviewSurface != null) targets.add(localPreviewSurface);

						cam.createCaptureSession(targets,new CameraCaptureSession.StateCallback(){
							@Override
							public void onConfigured(CameraCaptureSession s){
								session= s;
								try{
									CaptureRequest.Builder b= cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
									b.addTarget(encoderInputSurface);
									if(localPreviewSurface != null) b.addTarget(localPreviewSurface);
									b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,new Range<>(FRAMERATE,FRAMERATE));
									s.setRepeatingRequest(b.build(),null,cameraHandler);
									ok[0]= true;
								} catch (Exception e){
									Log.e("Eccles","capture session config failed",e);
								} finally {
									sem.release();
								}
							}
							@Override
							public void onConfigureFailed(CameraCaptureSession s){
								Log.e("Eccles","capture session configure failed",new RuntimeException());
								sem.release();
							}
						},cameraHandler);
					} catch (Exception e){
						Log.e("Eccles","createCaptureSession failed",e);
						sem.release();
					}
				}
				@Override
				public void onDisconnected(CameraDevice cam){
					cam.close();
					cameraDevice= null;
				}
				@Override
				public void onError(CameraDevice cam,int error){
					Log.e("Eccles","camera error "+error,new RuntimeException());
					cam.close();
					cameraDevice= null;
					sem.release();
				}
			},cameraHandler);

			sem.acquire();
			if(ok[0]) encoder.start();
			return ok[0];
		} catch (Exception e){
			Log.e("Eccles","VideoEncoder start failed",e);
			return false;
		}
	}

	private String pickCamera(CameraManager manager) throws CameraAccessException {
		String[] ids= manager.getCameraIdList();
		String fallback= null;
		for(String id: ids){
			CameraCharacteristics c= manager.getCameraCharacteristics(id);
			Integer facing= c.get(CameraCharacteristics.LENS_FACING);
			if(fallback == null) fallback= id;
			if(facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) return id;
		}
		return fallback;
	}

	/**
	 * Switches between front and back camera mid-call. Deliberately reuses the same encoder and
	 * encoderInputSurface - only which physical camera feeds them changes - so the ongoing
	 * encoded stream and the call's own signaling (codec config, session state) are completely
	 * undisturbed; this only closes and reopens the camera device/capture session, which is at
	 * most a brief gap in new frames, not a renegotiation of anything call-related. Runs on
	 * cameraHandler (not the caller's thread) since CameraDevice/CameraCaptureSession aren't
	 * thread-safe to touch from just anywhere, same as the rest of this class's camera calls.
	 */
	public void switchCamera(){
		if(cameraHandler == null) return;
		cameraHandler.post(()-> {
			try{
				if(session != null){ session.close(); session= null; }
				if(cameraDevice != null){ cameraDevice.close(); cameraDevice= null; }

				CameraManager manager= (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
				String newId= pickOtherCamera(manager,cameraId);
				if(newId == null){
					Log.w("Eccles","switchCamera: no other-facing camera available");
					return;
				}
				cameraId= newId;
				reportOrientation(manager);
				openCamera(manager);
			} catch (Exception e){
				Log.e("Eccles","switchCamera failed",e);
			}
		});
	}

	private String pickOtherCamera(CameraManager manager,String currentId) throws CameraAccessException {
		Integer currentFacing= null;
		try{
			currentFacing= manager.getCameraCharacteristics(currentId).get(CameraCharacteristics.LENS_FACING);
		} catch (Exception ignored){}
		for(String id: manager.getCameraIdList()){
			if(id.equals(currentId)) continue;
			Integer facing= manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
			if(currentFacing == null || facing == null || !currentFacing.equals(facing)) return id;
		}
		return null;
	}

	/** The same open-camera-then-create-capture-session sequence {@link #start} runs inline for
	 *  the initial camera, factored out here so {@link #switchCamera} can reuse it exactly
	 *  without needing to touch (and risk) start()'s own proven, already-working code path. */
	@SuppressLint("MissingPermission")
	private void openCamera(CameraManager manager) throws CameraAccessException {
		manager.openCamera(cameraId,new CameraDevice.StateCallback(){
			@Override
			public void onOpened(CameraDevice cam){
				cameraDevice= cam;
				try{
					List<Surface> targets= new ArrayList<>();
					targets.add(encoderInputSurface);
					if(localPreviewSurface != null) targets.add(localPreviewSurface);

					cam.createCaptureSession(targets,new CameraCaptureSession.StateCallback(){
						@Override
						public void onConfigured(CameraCaptureSession s){
							session= s;
							try{
								CaptureRequest.Builder b= cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
								b.addTarget(encoderInputSurface);
								if(localPreviewSurface != null) b.addTarget(localPreviewSurface);
								b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,new Range<>(FRAMERATE,FRAMERATE));
								s.setRepeatingRequest(b.build(),null,cameraHandler);
							} catch (Exception e){
								Log.e("Eccles","capture session config failed (switchCamera)",e);
							}
						}
						@Override
						public void onConfigureFailed(CameraCaptureSession s){
							Log.e("Eccles","capture session configure failed (switchCamera)",new RuntimeException());
						}
					},cameraHandler);
				} catch (Exception e){
					Log.e("Eccles","createCaptureSession failed (switchCamera)",e);
				}
			}
			@Override
			public void onDisconnected(CameraDevice cam){
				cam.close();
				cameraDevice= null;
			}
			@Override
			public void onError(CameraDevice cam,int error){
				Log.e("Eccles","camera error "+error,new RuntimeException());
				cam.close();
				cameraDevice= null;
			}
		},cameraHandler);
	}

	private boolean setupEncoder(){
		try{
			encoder= MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
			MediaCodecInfo.CodecCapabilities caps= encoder.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);

			MediaFormat format= MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,WIDTH,HEIGHT);
			format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
			format.setInteger(MediaFormat.KEY_BIT_RATE,BITRATE);
			format.setInteger(MediaFormat.KEY_FRAME_RATE,FRAMERATE);
			format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2);

			// Sharper picture at the *same* target bitrate rather than a higher one: VBR lets
			// the encoder spend more of that fixed budget on frames that actually need it
			// (motion, detail) and less on ones that don't (a mostly-static video-call frame),
			// instead of spending an equal share on every single frame the way CBR (the
			// implicit default when this isn't set) does.
			try{
				MediaCodecInfo.EncoderCapabilities encCaps= caps.getEncoderCapabilities();
				if(encCaps != null && encCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)){
					format.setInteger(MediaFormat.KEY_BITRATE_MODE,MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
				}
			} catch (Exception e){ Log.w("Eccles","VBR bitrate mode unavailable, leaving encoder default",e); }

			// Deliberately NOT setting KEY_PROFILE/KEY_LEVEL to anything above Baseline (the
			// implicit default). An earlier version of this tried AVCProfileMain for a sharper
			// picture at the same bitrate, checking it against *this* device's encoder
			// capabilities first - but that's the wrong capability to check. This is a
			// peer-to-peer call: the stream this device encodes gets decoded by whatever is on
			// the *other* end, an entirely different and often much weaker device, and the
			// encoder has no way to know what that device's decoder actually supports. Main uses
			// CABAC entropy coding that not every hardware decoder implements correctly - the
			// field symptom was exactly this: the picture looked fine locally and on capable
			// decoders, but came out as a solid-brown/scrambled mess on a budget/Android Go
			// device's decoder, which could encode it but choked decoding it. Baseline is
			// specifically the profile every H.264 decoder ever made is guaranteed to handle,
			// which matters far more here than a modest sharpness gain that only some of the
			// other end's possible devices would ever actually benefit from.
			encoder.setCallback(new MediaCodec.Callback(){
				@Override
				public void onInputBufferAvailable(MediaCodec c,int index){

				}
				@Override
				public void onOutputBufferAvailable(MediaCodec c,int index,MediaCodec.BufferInfo info){
					try{
						ByteBuffer out= c.getOutputBuffer(index);
						if(out != null && info.size>0){
							byte[] d= new byte[info.size];
							out.get(d);
							if((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0){
								if(callback != null) callback.onConfig(d);
							} else if(callback != null){
								boolean key= (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
								callback.onFrame(d,key);
							}
						}
					} catch (Exception e){
						Log.e("Eccles","VideoEncoder output error",e);
					} finally {
						c.releaseOutputBuffer(index,false);
					}
				}
				@Override
				public void onError(MediaCodec c,MediaCodec.CodecException e){
					Log.e("Eccles","VideoEncoder codec error",e);
					if(callback != null) callback.onError();
				}
				@Override
				public void onOutputFormatChanged(MediaCodec c,MediaFormat f){}
			},cameraHandler);
			encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
			encoderInputSurface= encoder.createInputSurface();
			return true;
		} catch (Exception e){
			Log.e("Eccles","VideoEncoder setup failed",e);
			return false;
		}
	}

	public void stop(){
		try{
			if(session != null){ session.close(); session= null; }
			if(cameraDevice != null){ cameraDevice.close(); cameraDevice= null; }
			if(encoder != null){ encoder.stop(); encoder.release(); encoder= null; }
			if(encoderInputSurface != null){ encoderInputSurface.release(); encoderInputSurface= null; }
			if(cameraThread != null){ cameraThread.quitSafely(); cameraThread= null; }
		} catch (Exception e){
			Log.e("Eccles","VideoEncoder stop failed",e);
		}
	}
}
