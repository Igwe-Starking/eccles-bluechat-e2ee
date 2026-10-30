package starking.eccles.bluechat.activities;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Point;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Chronometer;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import starking.eccles.Interface.EcclesMessage;
import starking.eccles.bluechat.EcclesActivity;
import starking.eccles.bluechat.R;
import starking.eccles.bluechat.bclassic.ClassicCompat;
import starking.eccles.bluechat.service.Caller;
import starking.eccles.data.EcclesStorage;
import starking.eccles.util.EcclesIcon;

public class CallActivity extends EcclesActivity {

	private BluetoothSocket socket;
	private BluetoothDevice device;
	private ImageView image;
	private TextView name;
	private FloatingActionButton accept,reject;
	private FloatingActionButton speakerButton;
	private Caller caller;
	public Chronometer dur;
	private ServiceConnection connection;
	private short type;
	private short maxRing= 0;
	private MediaPlayer rPlayer;
	private boolean ringed= false;

	public static final short TYPE_REQUEST= 0;
	public static final short TYPE_RECEIVE= 1;
	public static final short TYPE_IN_CALL= 2;
	public static final short TYPE_ENDED=3;
	public static final short TYPE_ACCEPTED= 4;
	public static final short TYPE_REJECTED= 5;
	public static final short TYPE_UNANSWERED= 6;
	public static final short TYPE_RINGING= 7;

	@SuppressLint("MissingPermission")
	@Override
	protected void onCreate(Bundle b){
		super.onCreate(b);

		requestWindowFeature(Window.FEATURE_NO_TITLE);

		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		device= getIntent().getExtras()!=null ? getIntent().getExtras().getParcelable("device") : null;
		if(device == null){
			Toast.makeText(this,"Invalid Bluetooth device",1).show();
			finish();
			return;
		}
		type= getIntent().getExtras().getShort("type");
		if(type == TYPE_RECEIVE){
			// This activity instance is the one answering notifyIncomingCall's full-screen
			// intent (or the user tapped the notification directly) - either way, its job is
			// done once we're actually here, including on the early-failure returns below.
			starking.eccles.util.EcclesNotifier.cancelIncomingCall(this,device);
		}
		socket= app.getSocket(device.getAddress());

		if(socket == null || !socket.isConnected()){
			Toast.makeText(this,starking.eccles.util.PeerStore.displayName(device)+" is offline", Toast.LENGTH_LONG).show();
			finish();
			return;
		}

		if(app.inCall && (app.callingDevice == null || !device.getAddress().equals(app.callingDevice.getAddress()))){
			Toast.makeText(this,"Can't start a call while already in a call", Toast.LENGTH_LONG).show();
			finish();
			return;
		}
		addContent();
		if(!requestPermission(new String[]{Manifest.permission.RECORD_AUDIO},RECORD_PERM)){
			// The permission dialog is asynchronous, so "not granted yet" is not "refused".
			// Previously this finished the call immediately - i.e. the call always died the first
			// time the dialog appeared. Now it waits; onRequestPermissionsResult() restarts this
			// screen on a grant, or ends the call on a refusal.
			waitingForPermission= true;
		}

	}

	private boolean waitingForPermission= false;

	@Override
	protected boolean isLockable(){ return false; }

	@Override
	public void onRequestPermissionsResult(int rc,String[] perm,int[] gr){
		if(!waitingForPermission || rc != RECORD_PERM){
			super.onRequestPermissionsResult(rc,perm,gr);
			return;
		}
		waitingForPermission= false;
		if(gr.length > 0 && gr[0] == android.content.pm.PackageManager.PERMISSION_GRANTED){
			recreate();
			return;
		}
		if(type == TYPE_RECEIVE){
			write(socket,new EcclesMessage(EcclesMessage.TYPE_CALL,EcclesMessage.SUBTYPE_CALL_ENDED));
		}
		Toast.makeText(this,"Can't start the call without microphone permission", Toast.LENGTH_LONG).show();
		finish();
	}

	@SuppressLint("MissingPermission")
	@Override
	public void onStart(){
		if(waitingForPermission){
			super.onStart();
			return;
		}
		handleCall(type);

		reject.setOnClickListener((View v)->{
			write(socket,new EcclesMessage(EcclesMessage.TYPE_CALL,EcclesMessage.SUBTYPE_CALL_REJECTED));
			app.callBase.updateNewMessage(device.getAddress(),"2");
			quit("CALL ENDED");
		});
		if(type == TYPE_REQUEST){
			name.setText("Calling "+starking.eccles.util.PeerStore.displayName(device));
			slideIn();

		} else {
			if(type== TYPE_RECEIVE) name.setText("Incoming Call from "+starking.eccles.util.PeerStore.displayName(device));

			accept.setOnClickListener((View v)->{
				write(socket,new EcclesMessage(EcclesMessage.TYPE_CALL,EcclesMessage.SUBTYPE_CALL_ACCEPTED));
				slideIn();
			});
		}
		type= 0;
		super.onStart();
	}
	public void handleCall(short type){
		switch(type){
			case TYPE_RECEIVE:
				ring();
				break;
			case TYPE_REQUEST:
				request();
				break;
			case TYPE_ENDED:
				quit("CALL ENDED");
				break;
			case TYPE_IN_CALL:
				refresh();
				break;
			case TYPE_ACCEPTED:
				start();
				break;
			case TYPE_UNANSWERED:
				quit("CALL NOT ANSWERED");
				break;
			case TYPE_REJECTED:
				quit("CALL REJECTED");
				break;
			case TYPE_RINGING:
				name.setText("Ringing...");
				ringed= true;
				try{
					rPlayer= MediaPlayer.create(this,R.raw.incoming_ring);
					rPlayer.setLooping(true);
					rPlayer.start();
				} catch (Exception e){ android.util.Log.e("CallActivity", "Suppressed exception", e); }
				break;
			default:
				throw new RuntimeException("unknown call type to handle");
		}
	}

	@SuppressLint("MissingPermission")
	private void request(){
		EcclesMessage m= new EcclesMessage(EcclesMessage.TYPE_CALL,EcclesMessage.SUBTYPE_CALL_REQUEST);

		new Handler(android.os.Looper.getMainLooper()).postDelayed(()->{
			if(!ringed){
				quit(starking.eccles.util.PeerStore.displayName(device)+" Cannot be reached");
			}
		},10000);
		write(socket,m);
		if(!app.callBase.insert(device,"1")){
			app.callBase.updateNewMessage(device.getAddress(),"1");
		}
	}

	private void ring(){
		rPlayer= MediaPlayer.create(this,EcclesStorage.getTone(this));
		rPlayer.setLooping(true);

		new Handler(android.os.Looper.getMainLooper()).postDelayed(()->{
			if(!app.inCall){
			write(socket,new EcclesMessage(EcclesMessage.TYPE_CALL,EcclesMessage.SUBTYPE_CALL_UNANSWERED));
			if(!app.callBase.insert(device,"3")){
				app.callBase.updateNewMessage(device.getAddress(),"3");
			}
			ClassicCompat.newMiss(CallActivity.this,false);
			try{
				rPlayer.stop();
				rPlayer.release();
				rPlayer= null;
			} catch (Exception e){ android.util.Log.e("CallActivity", "Suppressed exception", e); }

			quit("CALL ENDED");
			}
		},30000);
		rPlayer.start();
		write(socket,new EcclesMessage(EcclesMessage.TYPE_CALL,EcclesMessage.SUBTYPE_CALL_RINGING));
	}

	public void quit(String r){
		try{
			if(rPlayer != null) {
				rPlayer.stop();
				rPlayer.release();
			}
		} catch (Exception e){
			Log.e("Eccles","failed to stop player",e);
		}

		if(caller != null){
			try{

				unbindService(connection);
			} catch(Exception s){ android.util.Log.e("CallActivity", "Suppressed exception", s); }
		}
		name.setText(r);
		if(speakerButton != null) speakerButton.setVisibility(View.GONE);
		app.inCall= false;
		app.callingDevice= null;
		app.callDur= 0L;

		if(listener != null) listener.listen();
		if(reader != null && socket != null) reader.read(socket);
		new Handler(getMainLooper()).postDelayed(()->{
			finish();
		},3000);
	}
	/**
	 * Called when a TYPE_IN_CALL message arrives while already connected (see
	 * {@link #handleCall}). Currently a no-op: there is no additional UI state that needs
	 * updating on that event today (the call duration timer already runs independently via
	 * {@link #dur}), but this is left as an explicit hook rather than removed, since the
	 * TYPE_IN_CALL message type existing at all implies it was meant to carry some kind of
	 * mid-call state to refresh.
	 */
	public void refresh(){

	}

	@SuppressLint("MissingPermission")
	public void start(){
		try{
			rPlayer.stop();
			rPlayer.release();
			rPlayer= null;
		} catch (Exception e){ android.util.Log.e("CallActivity", "Suppressed exception", e); }

		if(app.pref.getBoolean("disconnect_oncall",false)){
			for(BluetoothSocket s:new java.util.ArrayList<>(app.connectionList.values())){
				if(!(s.getRemoteDevice().getAddress().equals(device.getAddress()))){
					try{
						onDisconnected(s.getRemoteDevice());
						s.close();
					} catch (Exception e){ Log.e("Eccles","failed to disconnect "+s.getRemoteDevice().getAddress()+" for call",e); }
				}
			}
		}
		app.callingDevice= device;
		Intent in= new Intent(this,Caller.class);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			startForegroundService(in);
		} else {
			startService(in);
		}
		reject.setOnClickListener((View cv)->{
			write(socket,new EcclesMessage(EcclesMessage.TYPE_CALL,EcclesMessage.SUBTYPE_CALL_ENDED));

		});
		name.setText("Connected to "+starking.eccles.util.PeerStore.displayName(device));
		dur.setVisibility(View.VISIBLE);
		dur.setBase(SystemClock.elapsedRealtime());
		dur.start();
		setupSpeakerToggle();

	}

	/** Shown only from here - once the call is actually connected (both on answering and on the
	 *  far end accepting), not during ringing, matching how WhatsApp/standard call UIs only
	 *  show in-call controls once there's an active call to control. Caller#startCall already
	 *  defaults audio to the speaker, so this starts reflecting that same "on" state rather
	 *  than defaulting to looking like it's off. */
	private void setupSpeakerToggle(){
		if(speakerButton == null) return;
		AudioManager audioManager= (AudioManager) getSystemService(AUDIO_SERVICE);
		if(audioManager == null) return;
		speakerButton.setVisibility(View.VISIBLE);
		updateSpeakerIcon(audioManager);
		speakerButton.setOnClickListener(v-> {
			Caller.toggleSpeaker(audioManager);
			updateSpeakerIcon(audioManager);
		});
	}

	private void updateSpeakerIcon(AudioManager audioManager){
		boolean onSpeaker= Caller.isOnSpeaker(audioManager);
		// Same icon throughout, tinted to show on/off - simpler and more reliable than hunting
		// for a system drawable that unambiguously means "earpiece" specifically.
		speakerButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(onSpeaker ? "#33ff33" : "#888888")));
	}

	public void slideIn(){
		Point p= new Point();
		getWindowManager().getDefaultDisplay().getSize(p);
		reject.measure(View.MeasureSpec.UNSPECIFIED,View.MeasureSpec.UNSPECIFIED);
		int sr= ((p.x/2)-(reject.getMeasuredWidth()/2));
		p= null;
		ObjectAnimator animator= ObjectAnimator.ofFloat(reject,View.TRANSLATION_X,0.0f,sr);
		animator.start();
		accept.setVisibility(View.GONE);
	}

	public void addContent(){
		setContentView(R.layout.activity_call);

		image= findViewById(R.id.caller_image);
		name= findViewById(R.id.caller);
		accept= findViewById(R.id.accept_bt);
		reject= findViewById(R.id.reject_bt);
		speakerButton= findViewById(R.id.speaker_bt);
		dur= findViewById(R.id.call_timer);
		if(app.callingDevice != null && app.callingDevice.getAddress().equals(device.getAddress()) && app.callDur>0){
			dur.setBase(app.callDur);
			dur.start();
		}
		image.setImageDrawable(EcclesIcon.resize(100,100,ClassicCompat.queryDeviceIcon(device.getAddress(),this),this));
	}

	@Override
	public void sent(BluetoothSocket s,EcclesMessage m) {
		super.sent(s, m);
		if (m.type == EcclesMessage.TYPE_CALL && m.subtype == EcclesMessage.SUBTYPE_CALL_ACCEPTED) {
			start();
			if (!app.callBase.insert(device, "2")) {
				app.callBase.updateNewMessage(device.getAddress(), "2");
			}
		}
		if (m.type == EcclesMessage.TYPE_CALL && m.subtype == EcclesMessage.SUBTYPE_CALL_ENDED) {
			app.stopService(new Intent(app, Caller.class));
		}
	}

	@Override
	public void onSaveInstanceState(Bundle b) {
		super.onSaveInstanceState(b);
		b.putLong("dur", dur.getBase());
		app.callDur = dur.getBase();
	}

	@Override
	public void onRestoreInstanceState(Bundle b){
		dur.setBase(b.getLong("dur"));
		dur.start();
	}

	@Override
	public void onPause(){
		try{
			rPlayer.stop();
			rPlayer.release();
		} catch (Exception e){ android.util.Log.e("CallActivity", "Suppressed exception", e); }
		super.onPause();
	}
}