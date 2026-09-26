package starking.eccles.stream;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

public class VideoDecoder {

	public interface Callback {
		/** See {@link AudioEncoder.Callback#onError()} for why this
		 *  exists; same threading caveat applies (codec callback thread, not UI thread). */
		void onError();
	}

	private MediaCodec decoder;
	private HandlerThread thread;
	private Handler handler;
	private Callback callback;
	private final LinkedBlockingQueue<Integer> freeInputIndices= new LinkedBlockingQueue<>();
	private volatile boolean running;

	public boolean start(byte[] csd,int width,int height,Surface target,Callback cb){
		this.callback= cb;
		try{
			thread= new HandlerThread("VideoDecoder");
			thread.start();
			handler= new Handler(thread.getLooper());

			MediaFormat format= MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height);
			format.setByteBuffer("csd-0",ByteBuffer.wrap(csd));

			decoder= MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
			decoder.setCallback(new MediaCodec.Callback(){
				@Override
				public void onInputBufferAvailable(MediaCodec c,int index){
					freeInputIndices.add(index);
				}
				@Override
				public void onOutputBufferAvailable(MediaCodec c,int index,MediaCodec.BufferInfo info){

					c.releaseOutputBuffer(index,info.size>0);
				}
				@Override
				public void onError(MediaCodec c,MediaCodec.CodecException e){
					Log.e("Eccles","VideoDecoder codec error",e);
					running= false;
					if(callback != null) callback.onError();
				}
				@Override
				public void onOutputFormatChanged(MediaCodec c,MediaFormat f){}
			},handler);
			decoder.configure(format,target,null,0);
			decoder.start();
			running= true;
			return true;
		} catch (Exception e){
			Log.e("Eccles","VideoDecoder start failed",e);
			return false;
		}
	}

	public void feed(byte[] nal,boolean keyFrame){
		if(!running || decoder == null) return;
		try{
			// Non-blocking for the same reason as AudioDecoder.feed(): called from the same
			// shared socket-reading thread, so a blocking wait here (previously up to 50ms)
			// directly stalls the network read loop - and stacked with AudioDecoder.feed()'s
			// own wait for a single message carrying both a video and audio payload, the old
			// blocking waits could compound to a ~70ms stall per message in the worst case.
			Integer index= freeInputIndices.poll();
			if(index == null) return;
			ByteBuffer buf= decoder.getInputBuffer(index);
			if(buf != null){
				buf.clear();
				buf.put(nal);
				decoder.queueInputBuffer(index,0,nal.length,System.nanoTime()/1000,keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
			}
		} catch (Exception e){
			Log.e("Eccles","VideoDecoder feed failed",e);
		}
	}

	public void stop(){
		running= false;
		try{
			if(decoder != null){
				decoder.stop();
				decoder.release();
			}
		} catch (Exception e){ Log.e("Eccles","VideoDecoder stop failed",e); }
		decoder= null;
		freeInputIndices.clear();
		if(thread != null){ thread.quitSafely(); thread= null; }
	}
}
