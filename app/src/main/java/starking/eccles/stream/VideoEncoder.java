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

	public static final int WIDTH= 320;
	public static final int HEIGHT= 240;

	public static final int BITRATE= 300_000;
	public static final int FRAMERATE= 15;

	private CameraDevice cameraDevice;
	private CameraCaptureSession session;
	private MediaCodec encoder;
	private Surface encoderInputSurface;
	private HandlerThread cameraThread;
	private Handler cameraHandler;
	private Callback callback;
	private final Context context;
	private String cameraId;

	public VideoEncoder(Context c){
		this.context= c;
	}

	@SuppressLint("MissingPermission")
	public boolean start(Surface localPreviewSurface,Callback cb){
		this.callback= cb;
		try{
			cameraThread= new HandlerThread("VideoEncoderCamera");
			cameraThread.start();
			cameraHandler= new Handler(cameraThread.getLooper());

			CameraManager manager= (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
			cameraId= pickCamera(manager);
			if(cameraId == null) return false;
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

	private boolean setupEncoder(){
		try{
			MediaFormat format= MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,WIDTH,HEIGHT);
			format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
			format.setInteger(MediaFormat.KEY_BIT_RATE,BITRATE);
			format.setInteger(MediaFormat.KEY_FRAME_RATE,FRAMERATE);
			format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2);

			encoder= MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
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
