package starking.eccles.bluechat.activities;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.ClipData;
import android.content.ClipDescription;
import android.graphics.Point;
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
import android.view.DragEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AbsoluteLayout;
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

	private SurfaceView view,video;
	private FloatingActionButton accept,reject;
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
	private Point screen;
	private short type;
	private AbsoluteLayout dragParent;

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

		boolean cameraOk=requestPermission(new String[]{Manifest.permission.CAMERA},CAMERA_PERM);
		boolean audioOk=requestPermission(new String[]{Manifest.permission.RECORD_AUDIO},RECORD_PERM);
		if(!cameraOk || !audioOk){
			Toast.makeText(this,"cant initiate video call, Camera or Microphone permission not granted",1).show();
			finish();
			return;
		}

		device= getIntent().getExtras()!=null ? getIntent().getExtras().getParcelable("device") : null;
		if(device == null){
			Toast.makeText(this,"invalid bluetooth device",1).show();
			finish();
			return;
		}
		socket= app.getSocket(device.getAddress());
		type= getIntent().getExtras().getShort("type");

		if(socket == null){
			Toast.makeText(this,device.getName()+" is not active",1).show();
			finish();
			return;
		}
		if(app.inCall){
			Toast.makeText(this,"Cant initiate call while in call",1).show();
			finish();
			return;
		}

		try{
			android.hardware.camera2.CameraManager cm= (android.hardware.camera2.CameraManager) getSystemService(CAMERA_SERVICE);
			String[] ids= cm.getCameraIdList();
			if(ids == null || ids.length==0) throw new IllegalStateException("no camera available");
		} catch (Exception e){
			Toast.makeText(this,"Cant initiate video call because no camera is available",1).show();
			finish();
			return;
		}

		addContent();
		getWindowManager().getDefaultDisplay().getSize(screen= new Point());
	}

	@Override
	public void onStart(){
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
				Toast.makeText(this,"initiate failed",1).show();
				quit("Call Initialization failed");

			}

			duration.setVisibility(View.VISIBLE);
			duration.setBase(SystemClock.elapsedRealtime());
			duration.start();
			app.inCall= true;
			slideIn(true);
		} catch (Exception e){
			quit(e.getMessage());
		}
	}

	public void request(){
		name.setText("Calling "+device.getName());
		slideIn(false);
		write(socket,new EcclesMessage(EcclesMessage.TYPE_VIDEO,EcclesMessage.SUBTYPE_CALL_REQUEST));
	}

	public void ring(){

		name.setText("Incoming Call from "+device.getName());
		p= MediaPlayer.create(this,EcclesStorage.getTone(this));
		p.setLooping(true);
		new Handler(android.os.Looper.getMainLooper()).postDelayed(()->{
			if(!app.inCall){
				write(socket,new EcclesMessage(EcclesMessage.TYPE_VIDEO,EcclesMessage.SUBTYPE_CALL_UNANSWERED));
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
						quit(device.getName()+" Not Reachable");
					}
				},10000);
				ringed= true;
				break;

		}
	}

	public void stop(){
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
		if(audioEncoder != null){ audioEncoder.stop(); audioEncoder= null; }
		if(audioDecoder != null){ audioDecoder.stop(); audioDecoder= null; }
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
		if(isFinishing()){
			quit("CALL ENDED");
			Toast.makeText(this,"Call Quited",1).show();
			try{ if(socket!=null) write(socket,new EcclesMessage(EcclesMessage.TYPE_VIDEO,EcclesMessage.SUBTYPE_CALL_ENDED)); }catch(Exception e){ Log.w("Eccles","unable to send video end",e); }
		}
		super.onStop();
	}

	public void addContent(){
		setContentView(R.layout.activity_video);

		accept= findViewById(R.id.accept_bt);
		reject= findViewById(R.id.reject_bt);
		view= findViewById(R.id.prev);
		video= findViewById(R.id.caller_video);
		duration= findViewById(R.id.duration);
		icon= findViewById(R.id.icon);
		name= findViewById(R.id.name);
		icon.setImageDrawable(EcclesIcon.resize(200,200,ClassicCompat.queryDeviceIcon(device.getAddress(),this),this));
		dragParent= findViewById(R.id.drag_parent);
	}

	@Override
	public void sent(BluetoothSocket s,EcclesMessage m){
		if(m.type== EcclesMessage.TYPE_VIDEO && m.subtype == EcclesMessage.SUBTYPE_CALL_ACCEPTED){
			start();
		}
	}
	@Override
	public void sentFailed(BluetoothSocket s,EcclesMessage m,String r){
		if(m.type== EcclesMessage.TYPE_VIDEO && m.subtype== EcclesMessage.SUBTYPE_CALL_ACCEPTED){
			quit(device.getName()+" out of range");
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
				Toast.makeText(this,"failed to start camera encoder",1).show();
				return false;
			}
			if(view != null){
				view.setOnTouchListener((View v,MotionEvent event)->{
					switch (event.getAction()){
						case MotionEvent.ACTION_DOWN:
							startDragging(v);
							break;
						case MotionEvent.ACTION_UP:
							v.performClick();
							break;
					}
					return true;
				});
			}

			track.play();
			reader= Executors.newSingleThreadExecutor();
			callReaderStarted= true;
			reader.execute(()->{
				while(app.inCall){
					try{
						EcclesMessage m= EcclesMessage.read(receiver);
						if(m == null || m.type != EcclesMessage.TYPE_VIDEO) continue;

						if(m.subtype==EcclesMessage.SUBTYPE_CALL_CONFIG){
							if(videoDecoder == null && m.video != null && video != null && video.getHolder().getSurface().isValid()){
								videoDecoder= new VideoDecoder();
								videoDecoder.start(m.video,VideoEncoder.WIDTH,VideoEncoder.HEIGHT,video.getHolder().getSurface(),
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

	public void startDragging(View v){

		View.OnDragListener dList= new View.OnDragListener(){
			@Override
			public boolean onDrag(View v,DragEvent event){
				switch (event.getAction()){
					case DragEvent.ACTION_DROP:
						AbsoluteLayout.LayoutParams p= (AbsoluteLayout.LayoutParams) v.getLayoutParams();
						p.x= (int)event.getX();
						p.y= (int)event.getY();
						v.setLayoutParams(p);
						return false;
				}
				return true;
			}
		};

		dragParent.setOnDragListener(dList);

		ClipData.Item item= new ClipData.Item("Eccles drag");
		String[] mType= {ClipDescription.MIMETYPE_TEXT_PLAIN};

		ClipData data= new ClipData("Eccles",mType,item);
		View.DragShadowBuilder b= new View.DragShadowBuilder(v);
		v.startDrag(data,b,null,0);
	}

}