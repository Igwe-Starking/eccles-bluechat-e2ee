package starking.eccles.stream;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

public class AudioDecoder {

	public interface Callback {
		void onDecoded(byte[] pcm);
		/** See {@link AudioEncoder.Callback#onError()} for why this
		 *  exists; same threading caveat applies (codec callback thread, not UI thread). */
		void onError();
	}

	private MediaCodec codec;
	private HandlerThread thread;
	private Handler handler;
	private Callback callback;
	private final LinkedBlockingQueue<Integer> freeInputIndices= new LinkedBlockingQueue<>();
	private volatile boolean running;

	public boolean start(byte[] csd,Callback cb){
		this.callback= cb;
		try{
			thread= new HandlerThread("AudioDecoder");
			thread.start();
			handler= new Handler(thread.getLooper());

			MediaFormat format= MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,AudioEncoder.SAMPLE_RATE,1);
			format.setByteBuffer("csd-0",ByteBuffer.wrap(csd));

			codec= MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
			codec.setCallback(new MediaCodec.Callback(){
				@Override
				public void onInputBufferAvailable(MediaCodec c,int index){
					freeInputIndices.add(index);
				}
				@Override
				public void onOutputBufferAvailable(MediaCodec c,int index,MediaCodec.BufferInfo info){
					try{
						ByteBuffer out= c.getOutputBuffer(index);
						if(out != null && info.size>0 && callback != null){
							byte[] d= new byte[info.size];
							out.get(d);
							callback.onDecoded(d);
						}
					} catch (Exception e){
						Log.e("Eccles","AudioDecoder output error",e);
					} finally {
						c.releaseOutputBuffer(index,false);
					}
				}
				@Override
				public void onError(MediaCodec c,MediaCodec.CodecException e){
					Log.e("Eccles","AudioDecoder codec error",e);
					running= false;
					if(callback != null) callback.onError();
				}
				@Override
				public void onOutputFormatChanged(MediaCodec c,MediaFormat f){}
			},handler);
			codec.configure(format,null,null,0);
			codec.start();
			running= true;
			return true;
		} catch (Exception e){
			Log.e("Eccles","AudioDecoder start failed",e);
			return false;
		}
	}

	public void feed(byte[] aac){
		if(!running || codec == null) return;
		try{
			// Non-blocking, unlike a fixed-timeout poll: this is called from VideoActivity's
			// shared socket-reading thread (the same one draining the Bluetooth connection for
			// both audio and video call data), so ANY blocking wait here directly stalls how
			// fast that thread can keep up with the incoming stream. Under codec backpressure,
			// dropping this frame immediately is the correct tradeoff for live audio - a
			// blocked network-read thread risks falling behind the stream entirely, which
			// compounds into growing lag rather than a single dropped frame.
			Integer index= freeInputIndices.poll();
			if(index == null) return;
			ByteBuffer buf= codec.getInputBuffer(index);
			if(buf != null){
				buf.clear();
				buf.put(aac);
				codec.queueInputBuffer(index,0,aac.length,System.nanoTime()/1000,0);
			}
		} catch (Exception e){
			Log.e("Eccles","AudioDecoder feed failed",e);
		}
	}

	public void stop(){
		running= false;
		try{
			if(codec != null){
				codec.stop();
				codec.release();
			}
		} catch (Exception e){ Log.e("Eccles","AudioDecoder stop failed",e); }
		codec= null;
		freeInputIndices.clear();
		if(thread != null){ thread.quitSafely(); thread= null; }
	}
}
