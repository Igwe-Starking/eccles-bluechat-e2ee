package starking.eccles.bluechat.activities;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.ActivityInfo;
import android.graphics.Point;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Chronometer;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import starking.eccles.Interface.EcclesMessage;
import starking.eccles.bluechat.EcclesActivity;
import starking.eccles.bluechat.Interface.EcclesReader;
import starking.eccles.bluechat.Interface.EcclesWriter;
import starking.eccles.bluechat.bclassic.ClassicCompat;
import starking.eccles.bluechat.service.Caller;
import starking.eccles.crypto.EccSession;
import starking.eccles.crypto.SessionManager;
import starking.eccles.data.EcclesStorage;
import starking.eccles.stream.AudioDecoder;
import starking.eccles.stream.AudioEncoder;
import starking.eccles.stream.VideoDecoder;
import starking.eccles.stream.VideoEncoder;
import starking.eccles.bluechat.R;
import starking.eccles.util.EcclesIcon;

public class VideoActivity extends EcclesActivity {

	private SurfaceView view;
	private TextureView video;
	/** Wraps video's SurfaceTexture for MediaCodec; created lazily once the TextureView is
	 * attached and available (see the isAvailable() check where the decoder is started). */
	private Surface remoteVideoSurface;
	private FloatingActionButton accept,reject;
	private FloatingActionButton speakerButton,switchCameraButton;
	private TextView name;
	private Chronometer duration;
	private ImageView icon;
	private BluetoothSocket socket;
	private BluetoothDevice device;
	private int maxRing= 0;
	private MediaPlayer p;
	private boolean ringed;
	private ExecutorService reader;
	private ExecutorService writer;
	private AudioRecord record;
	private AudioTrack track;
	private int previousAudioMode= AudioManager.MODE_NORMAL;
	private Caller.SpeakerRoute speakerRoute;
	/** See {@link Caller.AudioEffects}'s doc for what this actually fixes (the on-silence
	 * whine) and why it's needed in addition to the VOICE_COMMUNICATION audio source. */
	private Caller.AudioEffects audioEffects;
	private Point screen;
	private short type;

	/**
	 * Degrees the incoming (remote) video is rotated for display. Camera2 always hands the
	 * encoder landscape-oriented frames (see {@link VideoEncoder}'s WIDTH/HEIGHT comment), so
	 * the decoded output needs a compensating rotation to appear upright in this portrait-only
	 * activity. -90 is correct for the common case (sensor mounted at 270 degrees, which is
	 * what most front cameras report); if a device's peer video instead comes in upside-down or
	 * mirrored, flip the sign to 90 here - this is the only place that needs to change.
	 */
	private static final float REMOTE_VIDEO_ROTATION_DEGREES= -90f;

	private VideoEncoder videoEncoder;
	private VideoDecoder videoDecoder;
	private AudioEncoder audioEncoder;
	private AudioDecoder audioDecoder;
	private final AtomicReference<byte[]> videoCsd= new AtomicReference<>();
	private final AtomicReference<byte[]> audioCsd= new AtomicReference<>();
	private final AtomicBoolean configSent= new AtomicBoolean(false);
	/**
	 * Guards against a real race condition in the old code: {@link #quit} used to
	 * unconditionally call {@code super.reader.read(socket)} to resume the base chat-message
	 * reader immediately, regardless of whether this activity's own {@link #reader} executor
	 * thread (started in {@link #initiate}) had actually finished reading from the socket.
	 * Since {@code ExecutorService.shutdown()} does not interrupt an in-flight blocking
	 * {@code InputStream.read()}, that thread could still be blocked mid-read on the shared
	 * Bluetooth socket when the base reader resumed - two threads consuming the same
	 * InputStream concurrently, which can corrupt or drop whichever message either one was
	 * mid-read on.
	 * <p>
	 * {@link #callReaderStarted} distinguishes "no call-stream reader thread was ever created
	 * this session" (the {@link #initiate} failed before reaching that point - {@link #quit}
	 * must resume chat reading itself, since no thread ever will) from "one was created and
	 * might still be running" (only that thread, after it has genuinely finished its read
	 * loop, may resume chat reading). {@link #chatReaderResumed} then makes the actual resume
	 * idempotent regardless of which path reaches it, or in what order.
	 */
	private final AtomicBoolean chatReaderResumed= new AtomicBoolean(true);
	private volatile boolean callReaderStarted= false;

	public void onCreate(Bundle b){
		super.onCreate(b);

		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		// Without this, rotating the device mid-call would destroy and recreate this whole
		// Activity (there's no android:configChanges for orientation, and nothing here handled
		// it) - which would tear down the live camera session, both codecs, the reader/writer
		// threads and the encrypted-session handshake state, all of which live only in this
		// Activity's instance fields with no save/restore path. That's a dead or corrupted call,
		// not a resized one. CallActivity already locks to portrait for the same reason.
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
		// Needed before addContent() now, since addContent() sizes/rotates the video view using
		// it - previously this ran after addContent(), which is fine for its old use (slideIn())
		// but too late for that.
		getWindowManager().getDefaultDisplay().getSize(screen= new Point());

		// One combined request (two back-to-back requestPermissions() calls make the second one
		// get dropped), and no finish() while the dialog is up - it is asynchronous, so "not
		// granted yet" isn't "refused". onRequestPermissionsResult() restarts this screen on a
		// grant or ends the call on a refusal.
		if(!requestPermission(new String[]{Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO},CAMERA_PERM)){
			waitingForPermission= true;
			return;
		}

		device= getIntent().getExtras()!=null ? getIntent().getExtras().getParcelable("device") : null;
		if(device == null){
			Toast.makeText(this,"Invalid Bluetooth device",1).show();
			finish();
			return;
		}
		socket= app.getSocket(device.getAddress());
		type= getIntent().getExtras().getShort("type");
		if(type == CallActivity.TYPE_RECEIVE){
			// Same reasoning as CallActivity's onCreate - this instance is the one answering
			// notifyIncomingCall's full-screen intent (or a direct notification tap), so its
			// job is done as soon as we're here, including on the early-failure returns below.
			starking.eccles.util.EcclesNotifier.cancelIncomingCall(this,device);
		}

		if(socket == null){
			Toast.makeText(this,starking.eccles.util.PeerStore.displayName(device)+" is not active",1).show();
			finish();
			return;
		}
		if(app.inCall){
			Toast.makeText(this,"Can't start a call while already in a call",1).show();
			finish();
			return;
		}

		try{
			android.hardware.camera2.CameraManager cm= (android.hardware.camera2.CameraManager) getSystemService(CAMERA_SERVICE);
			String[] ids= cm.getCameraIdList();
			if(ids == null || ids.length==0) throw new IllegalStateException("no camera available");
		} catch (Exception e){
			Toast.makeText(this,"Can't start a video call: no camera is available",1).show();
			finish();
			return;
		}

		addContent();
	}

	private boolean waitingForPermission= false;

	@Override
	protected boolean isLockable(){ return false; }

	@Override
	public void onRequestPermissionsResult(int rc,String[] perm,int[] gr){
		if(!waitingForPermission || rc != CAMERA_PERM){
			super.onRequestPermissionsResult(rc,perm,gr);
			return;
		}
		waitingForPermission= false;
		boolean ok= gr.length > 0;
		for(int g: gr){ if(g != android.content.pm.PackageManager.PERMISSION_GRANTED) ok= false; }
		if(ok){
			recreate();
			return;
		}
		android.bluetooth.BluetoothDevice d= getIntent().getExtras()!=null ? getIntent().getExtras().getParcelable("device") : null;
		if(d != null && getIntent().getExtras().getShort("type") == CallActivity.TYPE_RECEIVE){
			android.bluetooth.BluetoothSocket sk= app.getSocket(d.getAddress());
			if(sk != null) write(sk,new EcclesMessage(EcclesMessage.TYPE_VIDEO,EcclesMessage.SUBTYPE_CALL_ENDED));
		}
		Toast.makeText(this,"Can't start the video call without camera and microphone permission",1).show();
		finish();
	}

	@Override
	public void onStart(){
		if(waitingForPermission){
			super.onStart();
			return;
		}
		handleCall(type);

		accept.setOnClickListener((View v)->{
			write(socket,new EcclesMessage(EcclesMessage.TYPE_VIDEO,EcclesMessage.SUBTYPE_CALL_ACCEPTED));
		});
		reject.setOnClickListener((View v)->{
			if(!app.inCall) {
				write(socket,new EcclesMessage(EcclesMessage.TYPE_VIDEO,EcclesMessage.SUBTYPE_CALL_REJECTED));
			} else {
				write(socket,new EcclesMessage(EcclesMessage.TYPE_VIDEO,EcclesMessage.SUBTYPE_CALL_ENDED));
			}
			app.videoBase.updateNewMessage(device.getAddress(),"2");
			quit("CALL ENDED");
		});
		type= 0;
		super.onStart();
	}

	public void start(){

		if(app.pref.getBoolean("disconnect_oncall",false)){
			// Iterate a defensive copy: disconnect() removes from app.connectionList as it
			// runs, and mutating a map while iterating its live values() view throws
			// ConcurrentModificationException (matches the same defensive copy CallActivity's
			// identical loop already uses).
			for(BluetoothSocket s:new java.util.ArrayList<>(app.connectionList.values())){
				if(!(s.getRemoteDevice().getAddress().equals(device.getAddress()))){
					try{
						// This loop existed with its exception handling already in place, but
						// the actual disconnect action was never implemented - the
						// "disconnect_oncall" setting was silently a no-op. warn=false since
						// this is an automatic background action the user already opted into
						// via the setting, not something that needs its own confirmation
						// dialog per device.
						disconnect(s.getRemoteDevice(),false);
					} catch (Exception e){ android.util.Log.e("VideoActivity", "Suppressed exception", e); }
				}
			}
		}

		if(p != null){
			p.stop();
			p.release();
		}

		try{
			if(!initiate()){
				Toast.makeText(this,"Failed to start the video call",1).show();
				quit("Call Initialization failed");

			}

			duration.setVisibility(View.VISIBLE);
			duration.setBase(SystemClock.elapsedRealtime());
			duration.start();
			app.inCall= true;
			slideIn(true);
			setupCallControls();
		} catch (Exception e){
			quit(e.getMessage());
		}
	}

	/** Shown only from here - once the call is actually connected, not during ringing, matching
	 *  how WhatsApp/standard call UIs only show in-call controls once there's an active call to
	 *  control. */
	private void setupCallControls(){
		if(speakerButton != null){
			AudioManager audioManager= (AudioManager) getSystemService(AUDIO_SERVICE);
			if(audioManager != null){
				speakerButton.setVisibility(View.VISIBLE);
				updateSpeakerIcon(audioManager);
				speakerButton.setOnClickListener(v-> {
					Caller.toggleSpeaker(audioManager);
					updateSpeakerIcon(audioManager);
				});
			}
		}
		if(switchCameraButton != null && videoEncoder != null){
			switchCameraButton.setVisibility(View.VISIBLE);
			switchCameraButton.setOnClickListener(v-> videoEncoder.switchCamera());
		}
	}

	private void updateSpeakerIcon(AudioManager audioManager){
		boolean onSpeaker= Caller.isOnSpeaker(audioManager);
		// Same icon throughout, tinted to show on/off - simpler and more reliable than hunting
		// for a system drawable that unambiguously means "earpiece" specifically.
		speakerButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(onSpeaker ? "#33ff33" : "#888888")));
	}

	public void request(){
		name.setText("Calling "+starking.eccles.util.PeerStore.displayName(device));
		slideIn(false);
		write(socket,new EcclesMessage(EcclesMessage.TYPE_VIDEO,EcclesMessage.SUBTYPE_CALL_REQUEST));
		// This whole family of app.videoBase.insert/updateNewMessage calls - here and in ring(),
		// the reject button, and sent() below - was simply missing entirely from VideoActivity,
		// which is the actual reason the Video tab never showed anything: nothing was ever
		// writing to that table in the first place, no matter how many video calls were made.
		// Mirrors CallActivity's request()/ring()/reject/sent() exactly, writing to videoBase
		// instead of callBase.
		if(!app.videoBase.insert(device,"1")){
			app.videoBase.updateNewMessage(device.getAddress(),"1");
		}
	}

	public void ring(){

		name.setText("Incoming Call from "+starking.eccles.util.PeerStore.displayName(device));
		p= MediaPlayer.create(this,EcclesStorage.getTone(this));
		p.setLooping(true);
		new Handler(android.os.Looper.getMainLooper()).postDelayed(()->{
			if(!app.inCall){
				write(socket,new EcclesMessage(EcclesMessage.TYPE_VIDEO,EcclesMessage.SUBTYPE_CALL_UNANSWERED));
				if(!app.videoBase.insert(device,"3")){
					app.videoBase.updateNewMessage(device.getAddress(),"3");
				}
				ClassicCompat.newMiss(VideoActivity.this,true);
				quit("CALL ENDED");
			}

		},10000);
		p.start();
		write(socket,new EcclesMessage(EcclesMessage.TYPE_VIDEO,EcclesMessage.SUBTYPE_CALL_RINGING));
	}

	public void quit(String r){
		name.setVisibility(View.VISIBLE);
		name.setText(r);
		app.inCall= false;
		stop();
		// If a call-stream reader thread was created this session, it - not this method - is
		// responsible for resuming chat reading, and only once it has actually finished its
		// read loop; see resumeChatReading() and the field docs above.
		if(!callReaderStarted){
			resumeChatReading();
		}
		new Handler(getMainLooper()).postDelayed(()->{
			finish();
		},8000);
	}

	/**
	 * Hands the socket's InputStream back to the base chat-message reader. Idempotent and
	 * safe to call from either {@link #quit} (when no call-stream reader thread was ever
	 * started) or from the tail of that reader thread's own loop (the normal case) - see the
	 * {@link #chatReaderResumed} field doc for why calling this from the wrong place/time was
	 * a real bug.
	 */
	private void resumeChatReading(){
		if(chatReaderResumed.compareAndSet(false,true)){
			if(super.reader != null && device != null && app.isConnected(device.getAddress())){
				super.reader.read(socket);
			}
		}
	}

	public void slideIn(boolean accepted){
		if(accepted){
		name.setVisibility(View.GONE);
		icon.setVisibility(View.GONE);
		}
		accept.setVisibility(View.GONE);
		reject.measure(0,0);
		ObjectAnimator oa= ObjectAnimator.ofFloat(reject,View.TRANSLATION_X,0.0f,((screen.x/2)-reject.getMeasuredWidth()));
		oa.start();
	}

	public void handleCall(short t){
		switch (t){
			case CallActivity.TYPE_REQUEST:
				request();
				break;
			case CallActivity.TYPE_RECEIVE:
				ring();
				break;
			case CallActivity.TYPE_ACCEPTED:
				runOnUiThread(()->{
					start();
				});
				break;
			case CallActivity.TYPE_UNANSWERED:
				quit("CALL NOT ANSWERED");
				break;
			case CallActivity.TYPE_REJECTED:
				quit("CALL REJECTED");
				break;
			case CallActivity.TYPE_ENDED:
				quit("CALL ENDED");
				break;
			case CallActivity.TYPE_RINGING:
				name.setText("Ringing");
				p= MediaPlayer.create(this,R.raw.incoming_ring);
				p.setLooping(true);
				p.start();
				new Handler(android.os.Looper.getMainLooper()).postDelayed(()->{
					if(!app.inCall || !ringed){
						quit(starking.eccles.util.PeerStore.displayName(device)+" Not Reachable");
					}
				},10000);
				ringed= true;
				break;

		}
	}

	public void stop(){
		if(speakerButton != null) speakerButton.setVisibility(View.GONE);
		if(switchCameraButton != null) switchCameraButton.setVisibility(View.GONE);
		try{
			if(p != null){
				p.stop();
				p.release();
			}
		} catch (Exception e){ android.util.Log.e("VideoActivity", "Suppressed exception", e); }
		if(reader != null){ reader.shutdown(); reader= null; }
		if(writer != null){ writer.shutdown(); writer= null; }
		if(videoEncoder != null){ videoEncoder.stop(); videoEncoder= null; }
		if(videoDecoder != null){ videoDecoder.stop(); videoDecoder= null; }
		if(remoteVideoSurface != null){
			try{ remoteVideoSurface.release(); } catch (Exception e){ android.util.Log.w("VideoActivity","error releasing remote video surface",e); }
			remoteVideoSurface= null;
		}
		if(audioEncoder != null){ audioEncoder.stop(); audioEncoder= null; }
		if(audioDecoder != null){ audioDecoder.stop(); audioDecoder= null; }
		if(audioEffects != null){ audioEffects.release(); audioEffects= null; }
		try{
			if(speakerRoute != null){ speakerRoute.restore(); speakerRoute= null; }
			AudioManager audioManager= (AudioManager) getSystemService(AUDIO_SERVICE);
			if(audioManager != null) audioManager.setMode(previousAudioMode);
		} catch (Exception e){ android.util.Log.w("VideoActivity","error restoring audio mode",e); }
		try{
			if(record != null){
				if(record.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING) record.stop();
				record.release();
				record= null;
			}
			if(track != null){
				if(track.getPlayState()==AudioTrack.PLAYSTATE_PLAYING) track.stop();
				track.release();
				track= null;
			}
		} catch (Exception e){ android.util.Log.e("VideoActivity", "Suppressed exception", e); }
	}

	@Override
	public void onStop(){
		if(isFinishing() && name != null){
			quit("CALL ENDED");
			Toast.makeText(this,"Call ended",1).show();
			try{ if(socket!=null) write(socket,new EcclesMessage(EcclesMessage.TYPE_VIDEO,EcclesMessage.SUBTYPE_CALL_ENDED)); }catch(Exception e){ Log.w("Eccles","unable to send video end",e); }
		}
		super.onStop();
	}

	public void addContent(){
		setContentView(R.layout.activity_video);

		accept= findViewById(R.id.accept_bt);
		reject= findViewById(R.id.reject_bt);
		speakerButton= findViewById(R.id.speaker_bt);
		switchCameraButton= findViewById(R.id.switch_camera_bt);
		view= findViewById(R.id.prev);
		video= findViewById(R.id.caller_video);
		duration= findViewById(R.id.duration);
		icon= findViewById(R.id.icon);
		name= findViewById(R.id.name);
		icon.setImageDrawable(EcclesIcon.resize(200,200,ClassicCompat.queryDeviceIcon(device.getAddress(),this),this));

		// Give the video view the screen's *swapped* dimensions (screen height as its width,
		// screen width as its height) so that once it's rotated 90 degrees its visual footprint exactly
		// matches the portrait screen again, instead of being cropped/letterboxed - see the
		// REMOTE_VIDEO_ROTATION_DEGREES field doc for why the rotation itself is needed.
		ViewGroup.LayoutParams rp= video.getLayoutParams();
		rp.width= screen.y;
		rp.height= screen.x;
		video.setLayoutParams(rp);
		video.setRotation(REMOTE_VIDEO_ROTATION_DEGREES);
	}

	@Override
	public void sent(BluetoothSocket s,EcclesMessage m){
		if(m.type== EcclesMessage.TYPE_VIDEO && m.subtype == EcclesMessage.SUBTYPE_CALL_ACCEPTED){
			start();
			if(!app.videoBase.insert(device,"2")){
				app.videoBase.updateNewMessage(device.getAddress(),"2");
			}
		}
	}
	@Override
	public void sentFailed(BluetoothSocket s,EcclesMessage m,String r){
		if(m.type== EcclesMessage.TYPE_VIDEO && m.subtype== EcclesMessage.SUBTYPE_CALL_ACCEPTED){
			quit(starking.eccles.util.PeerStore.displayName(device)+" out of range");
		}
	}

	/**
	 * Ends the call in response to an unrecoverable codec error. Codec {@code onError()}
	 * callbacks fire on that codec's own {@link Handler}/{@link HandlerThread}, never the UI
	 * thread, but {@link #quit} touches views - so this must post back via {@link #runOnUiThread}
	 * rather than call {@link #quit} directly, or it would crash with a wrong-thread exception.
	 */
	private void quitFromCodecError(String source){
		runOnUiThread(()-> quit(source+" failed, call ended"));
	}

	private synchronized boolean initiate(){
		try{
			final EccSession session= SessionManager.get(app).obtain(socket);
			final EcclesWriter sender= new EcclesWriter(socket.getOutputStream(),session);
			final EcclesReader receiver= new EcclesReader(socket.getInputStream(),session);
			record= Caller.initializeRecord(true);
			track= Caller.initializeTrack();
			if(record == null || track == null) return false;

			// Same fix as voice calls (see Caller.AudioEffects's doc) - without this, the
			// "wheeee" whine on silence happens here too, since VideoActivity manages its own
			// record/track independently of Caller/CallActivity rather than sharing its
			// startCall()/stopCall().
			AudioManager audioManager= (AudioManager) getSystemService(AUDIO_SERVICE);
			if(audioManager != null){
				previousAudioMode= audioManager.getMode();
				audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
				// Same reasoning as Caller#startCall - without this, call audio defaults to the
				// earpiece (quiet enough to need holding the phone to your ear), including on a
				// video call where you'd actually want to hear it hands-free.
				speakerRoute= Caller.SpeakerRoute.apply(audioManager);
			}
			audioEffects= Caller.AudioEffects.attach(record.getAudioSessionId());

			if(super.reader != null) super.reader.cancelReading(socket);
			// Reset per-session state (see the field docs): the previous session may have
			// already flipped these, and this must start each new call session clean.
			chatReaderResumed.set(false);
			callReaderStarted= false;

			app.inCall= true;

			final Runnable maybeSendConfig= ()->{
				byte[] vCsd= videoCsd.get();
				byte[] aCsd= audioCsd.get();
				if(vCsd != null && aCsd != null && configSent.compareAndSet(false,true)){
					try{
						new EcclesMessage(EcclesMessage.TYPE_VIDEO,EcclesMessage.SUBTYPE_CALL_CONFIG)
							.setVideo(vCsd).setVoice(aCsd).send(sender);
					} catch (Exception e){
						Log.e("Eccles","failed to send call config",e);
					}
				}
			};

			audioEncoder= new AudioEncoder();
			audioEncoder.start(new AudioEncoder.Callback(){
				@Override
				public void onConfig(byte[] csd){
					audioCsd.set(csd);
					maybeSendConfig.run();
				}
				@Override
				public void onEncoded(byte[] aac){
					try{
						new EcclesMessage(EcclesMessage.TYPE_VIDEO,EcclesMessage.SUBTYPE_CALL_ONGOING).setVoice(aac).send(sender);
					} catch (Exception e){
						Log.e("Eccles","error occured while sending call audio",e);
					}
				}
				@Override
				public void onError(){
					quitFromCodecError("Microphone encoder");
				}
			});

			writer= Executors.newSingleThreadExecutor();
			writer.execute(()->{
				Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
				record.startRecording();
				byte[] buf= new byte[Caller.FRAME_BYTES];
				while(app.inCall){
					try{
						int got= record.read(buf,0,buf.length);
						if(got>0) audioEncoder.feed(buf,got);
					} catch (Exception e){
						Log.e("Eccles","error occured reading mic audio",e);
					}
				}
			});

			videoEncoder= new VideoEncoder(this);
			boolean camOk= videoEncoder.start(view != null ? view.getHolder().getSurface() : null,new VideoEncoder.Callback(){
				@Override
				public void onConfig(byte[] csd){
					videoCsd.set(csd);
					maybeSendConfig.run();
				}
				@Override
				public void onFrame(byte[] data,boolean keyFrame){
					try{
						new EcclesMessage(EcclesMessage.TYPE_VIDEO,EcclesMessage.SUBTYPE_CALL_ONGOING).setVideo(data).send(sender);
					} catch (Exception e){
						Log.e("Eccles","error occured while sending video calls",e);
					}
				}
				@Override
				public void onError(){
					quitFromCodecError("Camera encoder");
				}
			});
			if(!camOk){
				Toast.makeText(this,"Failed to start the camera",1).show();
				return false;
			}
			if(view != null){
				view.setOnTouchListener(new PreviewDragListener());
			}

			videoEncoder.setOrientationListener(deg-> {
				try{
					if(sender != null) new EcclesMessage(EcclesMessage.TYPE_VIDEO,EcclesMessage.SUBTYPE_CAMERA_ROTATION).setData(new byte[]{(byte)(deg/90)}).send(sender);
				} catch(Exception e){ Log.w("Eccles","rotation signal failed",e); }
			});
			track.play();
			reader= Executors.newSingleThreadExecutor();
			callReaderStarted= true;
			reader.execute(()->{
				while(app.inCall){
					try{
						EcclesMessage m= EcclesMessage.read(receiver);
						if(m == null || m.type != EcclesMessage.TYPE_VIDEO) continue;

						if(m.subtype==EcclesMessage.SUBTYPE_CALL_ENDED){
							// The remote hanging up while a call is in progress was previously not
							// handled at all here - this subtype matched none of the branches
							// below, so it was silently dropped and this thread just kept blocking
							// on the next read. That left the call screen stuck (never learning
							// the other side hung up) and, worse, left this reader parked on the
							// socket indefinitely instead of promptly handing it back via
							// resumeChatReading() at the bottom of this loop - any chat message
							// that arrived in that gap would itself be silently discarded too,
							// since it isn't TYPE_VIDEO. Reacting here immediately fixes both.
							// break, not continue: app.inCall is set to false inside quit(), but
							// only once that posted Runnable actually runs on the UI thread: if
							// this loop instead looped back around to another blocking read, it
							// could sit there indefinitely if the remote never sends anything
							// else, right back to the original stuck-thread problem. Breaking
							// exits immediately and falls through to resumeChatReading() below.
							runOnUiThread(()-> quit("CALL ENDED"));
							break;
						}

						if(m.subtype==EcclesMessage.SUBTYPE_CAMERA_ROTATION){
							if(m.data != null && m.data.length > 0){
								final float extra= (m.data[0] & 3) * 90f;
								runOnUiThread(()-> { if(video != null) video.setRotation(REMOTE_VIDEO_ROTATION_DEGREES + extra); });
							}
							continue;
						}

						if(m.subtype==EcclesMessage.SUBTYPE_CALL_CONFIG){
							// TextureView (unlike SurfaceView) has no isValid()/getSurface() of its
							// own until its SurfaceTexture is actually attached; isAvailable() is
							// the equivalent readiness check, and the Surface has to be built once
							// from that SurfaceTexture and reused (rebuilding it on every check
							// would hand the decoder a different destination than whatever it was
							// already configured against).
							if(videoDecoder == null && m.video != null && video != null && video.isAvailable()){
								if(remoteVideoSurface == null) remoteVideoSurface= new Surface(video.getSurfaceTexture());
								videoDecoder= new VideoDecoder();
								videoDecoder.start(m.video,VideoEncoder.WIDTH,VideoEncoder.HEIGHT,remoteVideoSurface,
										()-> quitFromCodecError("Video decoder"));
							}
							if(audioDecoder == null && m.voice != null){
								audioDecoder= new AudioDecoder();
								audioDecoder.start(m.voice,new AudioDecoder.Callback(){
									@Override
									public void onDecoded(byte[] pcm){
										if(track != null) track.write(pcm,0,pcm.length);
									}
									@Override
									public void onError(){
										quitFromCodecError("Audio decoder");
									}
								});
							}
						} else if(m.subtype==EcclesMessage.SUBTYPE_CALL_ONGOING){
							if(m.video != null && videoDecoder != null) videoDecoder.feed(m.video,false);
							if(m.voice != null && audioDecoder != null) audioDecoder.feed(m.voice);
						}
					} catch (IOException ie){
						// This runs on the reader executor thread, not the UI thread - quit()
						// touches views (name.setVisibility/setText), so calling it directly
						// here would crash with CalledFromWrongThreadException. Every other
						// quit() call site in this class is already either a UI-thread
						// callback (button clicks, lifecycle methods) or explicitly posted via
						// runOnUiThread/Looper.getMainLooper() - this was the one direct,
						// unguarded exception, and the most likely one to actually fire, since
						// an abruptly dropped connection (the other party's app closing, going
						// out of range, etc.) is exactly what surfaces as an IOException here.
						runOnUiThread(()-> quit("CALL ENDED"));
					} catch (Exception e){
						Log.e("Eccles","Error processing call",e);
					} catch (OutOfMemoryError ooe){
						Log.e("Eccles","fatal error reading video calls",ooe);
					}
				}
				// The loop above only exits once app.inCall is false, i.e. after quit() has
				// already run - only now is it actually safe to hand the socket's InputStream
				// back to the base chat reader (see the chatReaderResumed field doc for why
				// doing this from quit() itself, immediately, was a real race condition).
				resumeChatReading();
			});

			return true;
		} catch (Exception e){
			Toast.makeText(this,e.toString(),1).show();
			return false;
		}
	}

	/**
	 * Moves the local preview (the small self-view) by hand, directly off raw touch events,
	 * instead of going through the platform's startDrag()/OnDragListener machinery. That
	 * machinery is built for drag-and-drop *between* views/apps (reordering a list, dropping
	 * onto a target) - using it just to reposition a single view within its own parent, as it
	 * was here, is swimming upstream against what it's designed for (it's also what was
	 * actually behind the "preview can't be dragged" bug from earlier: the system plays a
	 * reject/snap-back animation unless the ACTION_DROP handler explicitly accepts the drop,
	 * which is easy to get wrong and non-obvious to debug). This is the simpler, direct
	 * approach: ACTION_DOWN records the starting point, ACTION_MOVE updates the view's own
	 * position live by the finger's displacement each time, and ACTION_UP either leaves it
	 * where it landed (if it actually moved past the system's tap-vs-drag touch slop) or
	 * performs a click (if it didn't) - a tap and a drag are mutually exclusive outcomes of the
	 * same gesture, exactly like a button inside a ScrollView has to tell the two apart.
	 */
	private class PreviewDragListener implements View.OnTouchListener {
		private final int touchSlop= ViewConfiguration.get(VideoActivity.this).getScaledTouchSlop();
		private float downRawX,downRawY;
		private float startX,startY;
		private boolean moved;

		@Override
		public boolean onTouch(View v,MotionEvent event){
			// Positioned via plain View#setX/setY, not AbsoluteLayout.LayoutParams - that works
			// inside any ViewGroup (drag_parent is now a plain FrameLayout), since it's a
			// property of the view itself rather than something only one specific deprecated
			// parent type understood.
			switch (event.getActionMasked()){
				case MotionEvent.ACTION_DOWN:
					downRawX= event.getRawX();
					downRawY= event.getRawY();
					startX= v.getX();
					startY= v.getY();
					moved= false;
					return true;
				case MotionEvent.ACTION_MOVE:
					float dx= event.getRawX()-downRawX;
					float dy= event.getRawY()-downRawY;
					if(!moved && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) moved= true;
					if(moved){
						v.setX(startX+dx);
						v.setY(startY+dy);
					}
					return true;
				case MotionEvent.ACTION_UP:
					if(!moved) v.performClick();
					return true;
				case MotionEvent.ACTION_CANCEL:
					// Snap back if the gesture was interrupted mid-drag (e.g. a system dialog
					// popping up) rather than leaving it wherever it happened to be at that
					// instant.
					if(moved){
						v.setX(startX);
						v.setY(startY);
					}
					return true;
			}
			return false;
		}
	}

}