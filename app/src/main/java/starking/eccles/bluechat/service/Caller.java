package starking.eccles.bluechat.service;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.bluetooth.BluetoothSocket;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.StreamCorruptedException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import starking.eccles.Interface.EcclesMessage;
import starking.eccles.bluechat.EcclesApplication;
import starking.eccles.bluechat.Interface.EcclesReader;
import starking.eccles.bluechat.Interface.EcclesWriter;
import starking.eccles.bluechat.activities.CallActivity;
import starking.eccles.bluechat.activities.VideoActivity;
import starking.eccles.crypto.EccSession;
import starking.eccles.crypto.SessionManager;
import starking.eccles.util.ADPCM;
import starking.eccles.util.EcclesNotifier;

public class Caller extends EcclesService {

	public static final int SAMPLE_RATE= 16000;

	public static final int FRAME_BYTES= (SAMPLE_RATE*2*20)/1000;

	private BluetoothSocket socket;
	private BluetoothDevice device;
	EcclesApplication app;
	private ExecutorService caller;
	private ExecutorService writer;
	private boolean isForeground;
	private AudioTrack track;
	private AudioRecord record;
	/**
	 * Same race condition described in {@link VideoActivity}'s field docs, applied to voice
	 * calls: {@link #stopCall} used to unconditionally resume the base chat-message reader
	 * ({@code app.reader.read(socket)}) immediately after {@code caller.shutdown()}, which
	 * does not interrupt that executor's in-flight blocking {@code EcclesMessage.read(input)}
	 * call. That thread could still be reading from the shared Bluetooth socket when the base
	 * reader resumed - two threads consuming the same InputStream concurrently. This affects
	 * every voice call (video calls handle it the same way), so it is likely a significant
	 * contributor to intermittent post-call message corruption/loss.
	 * <p>
	 * {@link #callerReaderStarted} distinguishes "no call-stream reader thread was ever
	 * created this session" (an early failure in {@link #startCall}) from "one was created
	 * and might still be running" (only that thread, after it has genuinely finished its
	 * read loop, may resume chat reading). {@link #chatReaderResumed} then makes the actual
	 * resume idempotent regardless of which path reaches {@link #resumeChatReading}.
	 */
	private final java.util.concurrent.atomic.AtomicBoolean chatReaderResumed= new java.util.concurrent.atomic.AtomicBoolean(true);
	private volatile boolean callerReaderStarted= false;

	@SuppressLint("MissingPermission")
	@Override
	public void onCreate(){
		app= (EcclesApplication) getApplication();

		if(app.callingDevice != null){
			device= app.callingDevice;
			socket= app.getSocket(device.getAddress());
			startForeground(12,EcclesNotifier.notifyInCall(this,device,CallActivity.TYPE_IN_CALL));
			if(socket == null){
				if(app.currentActivity instanceof CallActivity){
					((CallActivity)app.currentActivity).quit(device.getName()+" Out of Range");
				}
				app.inCall= false;
				app.callingDevice= null;
				stopSelf();
				return;
			}
			startCall();
			isForeground= true;
		} else {
			stopSelf();
		}

	}
	@SuppressLint("MissingPermission")
	@Override
	public int onStartCommand(Intent intent, int flags, int startId){
		if(app.callingDevice != null && !isForeground){
			startForeground(12,EcclesNotifier.notifyInCall(this,app.callingDevice,CallActivity.TYPE_IN_CALL));
			socket= app.getSocket(device.getAddress());
			if(socket == null){
				if(app.currentActivity instanceof CallActivity){
					((CallActivity)app.currentActivity).quit(device.getName()+" Out of Range");
				}
				app.inCall= false;
				app.callingDevice= null;
				stopSelf();
				return START_NOT_STICKY;
			}
			startCall();
			isForeground= true;
		} else {
			stopSelf();
		}

		return super.onStartCommand(intent, flags, startId);
	}

	@SuppressLint("MissingPermission")
	public static AudioRecord initializeRecord(boolean vid){

		return initializeRecordLow(vid);
	}

	public static AudioTrack initializeTrack(){

		return initializeTrackLow();
	}

	public void startCall(){
		try{
			track= initializeTrack();
			record= initializeRecord(false);

			app.inCall= true;
			if(app.reader != null) app.reader.cancelReading(socket);
			// Reset per-session state (see the field docs): a previous call may have already
			// flipped these, and each new call session must start clean.
			chatReaderResumed.set(false);
			callerReaderStarted= false;

			final EccSession session= SessionManager.get(app).obtain(socket);
			final EcclesWriter output= new EcclesWriter(socket.getOutputStream(),session);
			final EcclesReader input= new EcclesReader(socket.getInputStream(),session);

			caller= Executors.newSingleThreadExecutor();
			callerReaderStarted= true;

			caller.execute(()->{
				try{
					while(app.inCall){
						Log.i("Eccles","caller started");
						try{
							EcclesMessage m= EcclesMessage.read(input);
							if(m != null){
								if(m.type != EcclesMessage.TYPE_CALL) continue;
								if(m.subtype==EcclesMessage.SUBTYPE_CALL_ONGOING){
									processCall(m);
									Log.i("Eccles","call received");
								} else if(m.subtype==EcclesMessage.SUBTYPE_CALL_ENDED){
									stopSelf();
								}
							}
						} catch (StreamCorruptedException sc){
							Log.e("Eccles","stream error from caller",sc);
						} catch (IOException ie){
							Log.e("Eccles","call disconnect",ie);
							String m= ie.getMessage();
							if(m != null && m.contains("socket closed")) stopSelf();
						} catch (Exception e){
							Log.e("Eccles","unknown error reading in call",e);
						}
					}
				}catch(Exception ignored){ android.util.Log.e("Caller", "Suppressed exception", ignored); }
				// The loop above only exits once app.inCall is false, i.e. after stopCall()
				// has already run - only now is it actually safe to hand the socket's
				// InputStream back to the base chat reader (see the chatReaderResumed field
				// doc for why doing this from stopCall() itself, immediately, was a race
				// condition).
				resumeChatReading();
			});
			track.play();
			record.startRecording();

			writer= Executors.newSingleThreadExecutor();
			writer.execute(()->{
				android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
				while(app.inCall){
					try{
						EcclesMessage m= read();
						if(m != null){
							m.send(output);
							Log.i("Eccles","call sent");
						}
					} catch (Exception io){
						Log.e("Eccles","error writing call",io);
					}
				}
			});
		} catch (Exception e2){
			stopSelf();
		}
	}

	protected synchronized EcclesMessage read(){
		if(record != null){
			try{
				if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING) record.startRecording();
				} catch (Exception i){
				Log.e("Eccles","Call read failed",i);
			}

			byte[] d= new byte[FRAME_BYTES];
			int got= record.read(d,0,d.length);
			if(got<=0) return null;

			return new EcclesMessage(EcclesMessage.TYPE_CALL,EcclesMessage.SUBTYPE_CALL_ONGOING).setVoice(ADPCM.encode(d,got));
		}
		Log.e("Eccles","null call",new NullPointerException());
		return null;
	}

	public synchronized void processCall(EcclesMessage m){

		if(m.voice == null) return;
		byte[] d= ADPCM.decode(m.voice);
		track.write(d,0,d.length);
		Log.i("Eccles","call processed");

	}

	public void stopCall(){
		try{
			track.release();
		} catch (Exception ignored){ android.util.Log.e("Caller", "Suppressed exception", ignored); }
		try{
			record.release();
			caller.shutdown();
			writer.shutdown();
		} catch (Exception ignored){ android.util.Log.e("Caller", "Suppressed exception", ignored); }
		app.inCall= false;
		app.callingDevice= null;
		app.callDur= 0L;
		// If a call-stream reader thread was created this session, it - not this method - is
		// responsible for resuming chat reading, and only once it has actually finished its
		// read loop; see resumeChatReading() and the field docs above.
		if(!callerReaderStarted){
			resumeChatReading();
		}
		if(app.currentActivity instanceof CallActivity){
			((CallActivity)app.currentActivity).quit("CALL ENDED");
		}
	}

	/**
	 * Hands the socket's InputStream back to the base chat-message reader. Idempotent and
	 * safe to call from either {@link #stopCall} (when no call-stream reader thread was ever
	 * started) or from the tail of that reader thread's own loop (the normal case).
	 */
	private void resumeChatReading(){
		if(chatReaderResumed.compareAndSet(false,true)){
			if(app.reader != null && socket != null && device != null && app.isConnected(device.getAddress())){
				app.reader.read(socket);
			}
		}
	}

	@SuppressLint("MissingPermission")
	public static AudioRecord initializeRecordLow(boolean vid){
		int minBuf= AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
		return new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minBuf,FRAME_BYTES*4));
	}
	public static AudioTrack initializeTrackLow(){
		int minBuf= AudioTrack.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
		return new AudioTrack(AudioManager.STREAM_VOICE_CALL,SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minBuf,FRAME_BYTES*4),AudioTrack.MODE_STREAM);
	}

	@Override
	public void onDestroy(){
		stopCall();
	}

}