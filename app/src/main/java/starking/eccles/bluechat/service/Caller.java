package starking.eccles.bluechat.service;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.bluetooth.BluetoothSocket;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
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
	 * Same race condition described in {@link starking.eccles.bluechat.activities.VideoActivity}'s field docs, applied to voice
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
	private int previousAudioMode= AudioManager.MODE_NORMAL;
	private SpeakerRoute speakerRoute;
	private AudioEffects effects;

	/**
	 * The actual source of the "wheeee" whine on silence: VOICE_COMMUNICATION alone is not
	 * enough for the platform to properly engage acoustic echo cancellation on a lot of real
	 * devices - the app also has to (a) put {@link AudioManager} in
	 * {@link AudioManager#MODE_IN_COMMUNICATION} (see {@link #startCall}) and (b) explicitly
	 * attach these effects to the capture session, or the mic keeps picking up whatever the
	 * earpiece/speaker just played back and feeding it back into the encoder - a classic
	 * acoustic feedback loop, which AGC then amplifies into a rising squeal specifically when
	 * there's no real voice around to mask it. Shared by both voice calls (this class) and
	 * video calls ({@link starking.eccles.bluechat.activities.VideoActivity}, which also uses
	 * {@link #initializeRecord}), since both were affected identically.
	 */
	public static class AudioEffects {
		private AcousticEchoCanceler aec;
		private NoiseSuppressor ns;
		private AutomaticGainControl agc;

		public static AudioEffects attach(int audioSessionId){
			AudioEffects e= new AudioEffects();
			try{
				if(AcousticEchoCanceler.isAvailable()){
					e.aec= AcousticEchoCanceler.create(audioSessionId);
					if(e.aec != null) e.aec.setEnabled(true);
				}
			} catch (Exception ex){ Log.w("Eccles","AcousticEchoCanceler unavailable",ex); }
			try{
				if(NoiseSuppressor.isAvailable()){
					e.ns= NoiseSuppressor.create(audioSessionId);
					if(e.ns != null) e.ns.setEnabled(true);
				}
			} catch (Exception ex){ Log.w("Eccles","NoiseSuppressor unavailable",ex); }
			try{
				if(AutomaticGainControl.isAvailable()){
					e.agc= AutomaticGainControl.create(audioSessionId);
					if(e.agc != null) e.agc.setEnabled(true);
				}
			} catch (Exception ex){ Log.w("Eccles","AutomaticGainControl unavailable",ex); }
			return e;
		}

		public void release(){
			try{ if(aec != null) aec.release(); } catch (Exception ignored){}
			try{ if(ns != null) ns.release(); } catch (Exception ignored){}
			try{ if(agc != null) agc.release(); } catch (Exception ignored){}
			aec= null; ns= null; agc= null;
		}
	}

	/**
	 * Routes call audio to the built-in speaker instead of the earpiece. {@link
	 * AudioManager#setSpeakerphoneOn} alone (the only mechanism that exists pre-API 31) isn't
	 * consistently honored on every OEM skin in {@code MODE_IN_COMMUNICATION} - on API 31+,
	 * {@link AudioManager#setCommunicationDevice} is the newer, more direct mechanism (it asks
	 * for a specific output device rather than toggling a boolean the platform is free to
	 * reinterpret), so this uses that where available and still also sets the boolean as a
	 * belt-and-braces fallback for skins that need it.
	 */
	public static class SpeakerRoute {
		private final AudioManager audioManager;
		private final boolean previousSpeakerphoneOn;
		private final AudioDeviceInfo previousCommunicationDevice;
		private final int previousVoiceCallVolume;

		private SpeakerRoute(AudioManager am,boolean prevSpeakerphoneOn,AudioDeviceInfo prevDevice,int prevVolume){
			this.audioManager= am;
			this.previousSpeakerphoneOn= prevSpeakerphoneOn;
			this.previousCommunicationDevice= prevDevice;
			this.previousVoiceCallVolume= prevVolume;
		}

		public static SpeakerRoute apply(AudioManager am){
			boolean prevSpeakerphoneOn= am.isSpeakerphoneOn();
			int prevVolume= am.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
			AudioDeviceInfo prevDevice= null;
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
				try{
					prevDevice= am.getCommunicationDevice();
					for(AudioDeviceInfo info: am.getAvailableCommunicationDevices()){
						if(info.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER){
							am.setCommunicationDevice(info);
							break;
						}
					}
				} catch (Exception e){ Log.w("Eccles","setCommunicationDevice(speaker) failed, falling back to setSpeakerphoneOn only",e); }
			}
			am.setSpeakerphoneOn(true);
			capVolumeForEcho(am);
			return new SpeakerRoute(am,prevSpeakerphoneOn,prevDevice,prevVolume);
		}

		public void restore(){
			try{
				audioManager.setSpeakerphoneOn(previousSpeakerphoneOn);
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
					if(previousCommunicationDevice != null) audioManager.setCommunicationDevice(previousCommunicationDevice);
					else audioManager.clearCommunicationDevice();
				}
				audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL,previousVoiceCallVolume,0);
			} catch (Exception e){ Log.w("Eccles","error restoring audio routing",e); }
		}
	}

	/**
	 * Another echo mitigation, on top of AcousticEchoCanceler/NoiseSuppressor/AGC: less
	 * acoustic energy coming out of the speaker in the first place means less of it for the mic
	 * to pick back up and for AEC to have to cancel out - which matters most exactly when AEC
	 * alone isn't fully up to the job, as on cheaper/less well-tuned hardware specifically in
	 * speaker mode (earpiece mode doesn't have this problem nearly as badly, since the earpiece
	 * is quiet and aimed away from the mic by design). Only ever caps *down* to 85% of max,
	 * never raises - if the person already had it lower than that, their choice is left alone.
	 * Still clearly louder than earpiece, just not pinned at the single loudest possible
	 * setting, which is also usually where echo is most audible. Called both when a call
	 * starts (SpeakerRoute.apply, which defaults to speaker) and when the in-call speaker
	 * button is used to switch back to speaker (toggleSpeaker).
	 */
	private static void capVolumeForEcho(AudioManager am){
		try{
			int max= am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
			int cap= (int) Math.round(max * 0.85);
			if(am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) > cap){
				am.setStreamVolume(AudioManager.STREAM_VOICE_CALL,cap,0);
			}
		} catch (Exception e){ Log.w("Eccles","error capping call volume",e); }
	}

	/** Whether call audio is currently coming out of the loudspeaker (as opposed to the
	 *  earpiece) - used by CallActivity/VideoActivity's speaker-toggle button to show the
	 *  correct initial state and to decide which way a tap should switch. */
	public static boolean isOnSpeaker(AudioManager am){
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
			try{
				AudioDeviceInfo dev= am.getCommunicationDevice();
				if(dev != null) return dev.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
			} catch (Exception ignored){}
		}
		return am.isSpeakerphoneOn();
	}

	/** Flips call audio between the loudspeaker and the earpiece - the user-facing counterpart
	 *  to {@link SpeakerRoute}, which only ever routes to the speaker (the call-start default).
	 *  Returns the new state (true = now on speaker). */
	public static boolean toggleSpeaker(AudioManager am){
		boolean switchingToSpeaker= !isOnSpeaker(am);
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
			try{
				int wanted= switchingToSpeaker ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
				for(AudioDeviceInfo info: am.getAvailableCommunicationDevices()){
					if(info.getType() == wanted){
						am.setCommunicationDevice(info);
						break;
					}
				}
			} catch (Exception e){ Log.w("Eccles","setCommunicationDevice failed while toggling speaker",e); }
		}
		am.setSpeakerphoneOn(switchingToSpeaker);
		if(switchingToSpeaker) capVolumeForEcho(am);
		return switchingToSpeaker;
	}

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
					((CallActivity)app.currentActivity).quit(starking.eccles.util.PeerStore.displayName(device)+" Out of Range");
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
		/* causes voice call to quit immediately its started
		if(app.callingDevice != null && !isForeground){
			startForeground(12,EcclesNotifier.notifyInCall(this,app.callingDevice,CallActivity.TYPE_IN_CALL));
			socket= app.getSocket(device.getAddress());
			if(socket == null){
				if(app.currentActivity instanceof CallActivity){
					((CallActivity)app.currentActivity).quit(starking.eccles.util.PeerStore.displayName(device)+" Out of Range");
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
		} */
		if(app.callingDevice == null || app.getSocket(device.getAddress()) == null){
			stopSelf();
			return START_NOT_STICKY;
		}
		// else: already started in onCreate() — nothing more to do here.

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
			// MODE_IN_COMMUNICATION is what actually engages the device's voice-call audio
			// path (proper gain staging, routing, and - critically - the reference signal the
			// platform's echo canceller needs) for a VOICE_COMMUNICATION-sourced AudioRecord.
			// Without it, this was capturing/playing back closer to MODE_NORMAL media
			// semantics, which on many devices leaves AEC effectively inactive.
			AudioManager audioManager= (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
			if(audioManager != null){
				previousAudioMode= audioManager.getMode();
				audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
				// track plays on STREAM_VOICE_CALL, which in MODE_IN_COMMUNICATION defaults to
				// the earpiece - quiet enough that you'd have to hold the phone right up to your
				// ear to hear it, same as a regular phone call defaults to earpiece. Routing to
				// the speaker explicitly is what actually makes it audible at a normal distance.
				speakerRoute= SpeakerRoute.apply(audioManager);
			}

			track= initializeTrack();
			record= initializeRecord(false);
			effects= AudioEffects.attach(record.getAudioSessionId());

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
		if(effects != null){ effects.release(); effects= null; }
		try{
			if(speakerRoute != null){ speakerRoute.restore(); speakerRoute= null; }
			AudioManager audioManager= (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
			if(audioManager != null) audioManager.setMode(previousAudioMode);
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