package starking.eccles.bluechat.activities;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.graphics.Point;
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
			Toast.makeText(this,"invalid bluetooth device",1).show();
			finish();
			return;
		}
		type= getIntent().getExtras().getShort("type");
		socket= app.getSocket(device.getAddress());

		if(socket == null || !socket.isConnected()){
			Toast.makeText(this,device.getName()+" is offline", Toast.LENGTH_LONG).show();
			finish();
			return;
		}

		if(app.inCall && (app.callingDevice == null || !device.getAddress().equals(app.callingDevice.getAddress()))){
			Toast.makeText(this,"cant initiate call while in call", Toast.LENGTH_LONG).show();
			finish();
			return;
		}
		addContent();
		if(!requestPermission(new String[]{Manifest.permission.RECORD_AUDIO},RECORD_PERM)){
				if(type == TYPE_RECEIVE){
					write(socket,new EcclesMessage(EcclesMessage.TYPE_CALL,EcclesMessage.SUBTYPE_CALL_ENDED));
				}

				Toast.makeText(this,"cant initiate call without microphone permission", Toast.LENGTH_LONG).show();
				finish();
				return;
		}

	}

	@SuppressLint("MissingPermission")
	@Override
	public void onStart(){
		handleCall(type);

		reject.setOnClickListener((View v)->{
			write(socket,new EcclesMessage(EcclesMessage.TYPE_CALL,EcclesMessage.SUBTYPE_CALL_REJECTED));
			app.callBase.updateNewMessage(device.getAddress(),"2");
			quit("CALL ENDED");
		});
		if(type == TYPE_REQUEST){
			name.setText("Calling "+device.getName());
			slideIn();

		} else {
			if(type== TYPE_RECEIVE) name.setText("Incoming Call from "+device.getName());

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
				quit(device.getName()+" Cannot be reached");
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
			rPlayer.stop();
			rPlayer.release();
		} catch (Exception e){
			Log.e("Eccles","failed to stop player",e);
		}

		if(caller != null){
			try{

				unbindService(connection);
			} catch(Exception s){ android.util.Log.e("CallActivity", "Suppressed exception", s); }
		}
		name.setText(r);
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
		name.setText("Connected to "+device.getName());
		dur.setVisibility(View.VISIBLE);
		dur.setBase(SystemClock.elapsedRealtime());
		dur.start();

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