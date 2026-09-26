package starking.eccles.stream;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

public class AudioEncoder {

	public interface Callback {

		void onConfig(byte[] csd);
		void onEncoded(byte[] aac);
		/**
		 * Called when the codec enters an unrecoverable error state. Without this, a codec
		 * crash mid-call previously went nowhere but a log line: {@code running} stayed true,
		 * every subsequent {@link #feed} call kept throwing (caught and logged) against a dead
		 * codec, and the call UI kept showing "connected" with audio silently gone - a
		 * confusing failure mode with no way to tell it had happened short of noticing the
		 * silence. Fired on the codec's callback {@link Handler} thread, not the UI thread -
		 * implementations that touch views must post back to the UI thread themselves.
		 */
		void onError();
	}

	public static final int SAMPLE_RATE= 16000;
	public static final int BIT_RATE= 24000;

	private MediaCodec codec;
	private HandlerThread thread;
	private Handler handler;
	private Callback callback;
	private final LinkedBlockingQueue<Integer> freeInputIndices= new LinkedBlockingQueue<>();
	private volatile boolean running;

	public boolean start(Callback cb){
		this.callback= cb;
		try{
			thread= new HandlerThread("AudioEncoder");
			thread.start();
			handler= new Handler(thread.getLooper());

			MediaFormat format= MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,SAMPLE_RATE,1);
			format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
			format.setInteger(MediaFormat.KEY_BIT_RATE,BIT_RATE);
			format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,4096);

			codec= MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
			codec.setCallback(new MediaCodec.Callback(){
				@Override
				public void onInputBufferAvailable(MediaCodec c,int index){
					freeInputIndices.add(index);
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
								callback.onEncoded(d);
							}
						}
					} catch (Exception e){
						Log.e("Eccles","AudioEncoder output error",e);
					} finally {
						c.releaseOutputBuffer(index,false);
					}
				}
				@Override
				public void onError(MediaCodec c,MediaCodec.CodecException e){
					Log.e("Eccles","AudioEncoder codec error",e);
					running= false;
					if(callback != null) callback.onError();
				}
				@Override
				public void onOutputFormatChanged(MediaCodec c,MediaFormat f){}
			},handler);
			codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
			codec.start();
			running= true;
			return true;
		} catch (Exception e){
			Log.e("Eccles","AudioEncoder start failed",e);
			return false;
		}
	}

	public void feed(byte[] pcm,int len){
		if(!running || codec == null) return;
		try{
			// Non-blocking: this is fed from AudioEncoder's own dedicated mic-capture thread
			// (not shared with anything else), so a brief wait here is low-risk, but keeping
			// it non-blocking is simple, consistent with the decoders below, and never adds
			// latency to mic capture even under codec backpressure.
			Integer index= freeInputIndices.poll();
			if(index == null) return;
			ByteBuffer buf= codec.getInputBuffer(index);
			if(buf != null){
				buf.clear();
				buf.put(pcm,0,len);
				codec.queueInputBuffer(index,0,len,System.nanoTime()/1000,0);
			}
		} catch (Exception e){
			Log.e("Eccles","AudioEncoder feed failed",e);
		}
	}

	public void stop(){
		running= false;
		try{
			if(codec != null){
				codec.stop();
				codec.release();
			}
		} catch (Exception e){ Log.e("Eccles","AudioEncoder stop failed",e); }
		codec= null;
		freeInputIndices.clear();
		if(thread != null){ thread.quitSafely(); thread= null; }
	}
}
