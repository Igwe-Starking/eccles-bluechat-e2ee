package starking.eccles.bluechat;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
import java.util.concurrent.ConcurrentHashMap;
import androidx.multidex.MultiDexApplication;
import androidx.preference.PreferenceManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import starking.eccles.bluechat.activities.EcclesOptions;
import starking.eccles.bluechat.bclassic.ClassicCompat;
import starking.eccles.bluechat.Interface.EcclesWriter;
import starking.eccles.bluechat.service.Caller;
import starking.eccles.bluechat.service.EcclesService;
import starking.eccles.bluechat.service.Listener;
import starking.eccles.bluechat.service.Reader;
import starking.eccles.crypto.EccSession;
import starking.eccles.crypto.SessionManager;
import starking.eccles.data.ChatBase;
import starking.eccles.data.EcclesData;
import starking.eccles.data.EcclesStorage;
import starking.eccles.Interface.EcclesMessage;
import starking.eccles.receivers.BluetoothStateReceiver;
import starking.eccles.util.EcclesIcon;

public class EcclesApplication extends MultiDexApplication implements Listener.ServerStatus {

	public BluetoothAdapter adapter;
	public String name;
	public String address;
	private boolean tryEnable= false;
	public boolean isOn;
	public boolean turnedOn= false;
	public EcclesActivity currentActivity;
	/**
	 * ConcurrentHashMap, not ArrayMap: this is read and mutated from background services
	 * (Listener/Reader/Caller), BroadcastReceivers (AvailReceiver), and Activities/Fragments
	 * concurrently. ArrayMap is not a concurrent collection - concurrent access to it can throw
	 * ConcurrentModificationException or corrupt its internal state, unlike this.
	 */
	public ConcurrentHashMap<String,BluetoothSocket> connectionList;
	public EcclesData chatBase,callBase,videoBase;
	public ChatBase chatStore;
	public boolean inCall= false;
	public Reader reader;
	public Listener listener;
	public SharedPreferences pref;
	public ServiceConnection connection;
	public boolean databaseOpen= false;
	public BluetoothManager manager;
	public BluetoothDevice callingDevice;
	public long callDur;
	public Caller caller;

	public void onCreate(){
			super.onCreate();
			try{

			adapter= BluetoothAdapter.getDefaultAdapter();
			pref= PreferenceManager.getDefaultSharedPreferences(this);
			EcclesStorage.clearPlaintextCache(this);
			if(adapter != null){
				name= adapter.getName();
				isOn= adapter.isEnabled();
				openDataBase();
			}
		reader= Reader.aquire(this);
		connectionList= new ConcurrentHashMap<>();

		// startForegroundService() (not the plain startService()) is required on API 26+ when
		// the started service intends to promote itself to a foreground service shortly after
		// (see Listener#onCreate()) - ContextCompat.startForegroundService() handles the
		// pre-26 fallback automatically.
		androidx.core.content.ContextCompat.startForegroundService(this,new Intent(this,Listener.class));

		if(listener == null){
			connection= new ServiceConnection(){
				@Override
				public void onServiceConnected(ComponentName name,IBinder binder){
					EcclesService es= (EcclesService)((EcclesService.Channel)binder).getService();

						listener= (Listener) es;
						// Registered exactly once, here, permanently for the life of the
						// process - not per-Activity. Previously each EcclesActivity/
						// MajorActivity re-registered itself as the listener on every
						// onCreate()/onResume(), but nothing ever cleared that registration
						// when an Activity was destroyed (there was no onDestroy() override
						// doing so), so Listener.status ended up holding a reference to the
						// last-destroyed Activity once the app was fully backgrounded - a real
						// memory leak (that entire Activity's view hierarchy stayed reachable),
						// and meant background connection acceptance depended on a stale
						// object instead of something that's always valid. EcclesApplication
						// never gets destroyed while the process lives, so this registration
						// never goes stale and never needs to be repeated.
						listener.setStatusListener(EcclesApplication.this);
				}
				@Override
				public void onServiceDisconnected(ComponentName name){

					connection= null;
				}
			};

			bindService(new Intent(this,Listener.class),connection,BIND_AUTO_CREATE);

			IntentFilter fit= new IntentFilter();
			fit.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
			androidx.core.content.ContextCompat.registerReceiver(this,new BluetoothStateReceiver(),fit,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
		}

	} catch (Exception e){ Log.e("EcclesApplication", "Suppressed exception", e); }

	}

	public boolean isConnected(String add){
		return connectionList.containsKey(add);
	}

	public boolean writeHeadless(BluetoothSocket socket,EcclesMessage message){
		if(socket == null || message == null) return false;
		try{
			EccSession session= SessionManager.get(this).obtain(socket);
			EcclesWriter ot= new EcclesWriter(socket.getOutputStream(),session);
			return message.send(ot);
		} catch (Exception e){
			Log.e("EcclesApplication","headless send failed",e);
			return false;
		}
	}

	public BluetoothSocket getSocket(String add){
		return connectionList.get(add);
	}

	// --- Listener.ServerStatus implementation ---
	//
	// Previously implemented by EcclesActivity and re-registered on every Activity's
	// onCreate()/onResume() - see the registration comment in onServiceConnected() above for
	// why that went stale. This version is Activity-independent: every dependency it uses
	// (SharedPreferences, Reader, writeHeadless()) only needs a Context, which
	// EcclesApplication always validly is. The one place the original had a UI side effect
	// (a Toast on read failure) is preserved here, but only fires when an Activity actually
	// happens to be in the foreground right now - silently skipped otherwise, which is correct:
	// there is no user to show a Toast to when the app is backgrounded, and no Activity to show
	// it on regardless.

	@Override
	public void onActive(){}

	@Override
	public void onFailed(String s){}

	@Override
	public boolean onAccepted(BluetoothSocket s){
		if(s == null) return false;
		try {
			if(s.getRemoteDevice().getBondState() != BluetoothDevice.BOND_BONDED){
				Log.w("Eccles","Rejected unpaired Bluetooth peer: "+s.getRemoteDevice().getAddress());
				s.close(); return false;
			}
		} catch(Exception e){
			Log.e("Eccles","Unable to verify Bluetooth bond",e);
			try{s.close();}catch(Exception ignored){}
			return false;
		}
		if(getSharedPreferences("Blocked_list",MODE_PRIVATE).getBoolean(s.getRemoteDevice().getAddress(),false)){
			try{
				s.close();
				return false;
			} catch (Exception r){ Log.e("EcclesApplication", "Suppressed exception", r); }
		}
		connectionList.putIfAbsent(s.getRemoteDevice().getAddress(),s);
		if(reader == null) reader= Reader.aquire(this);
		boolean readOk= false;
		for(int i= 0;i<10;i++){
			if(reader.read(s)){ readOk= true; break; }
		}
		if(!readOk && currentActivity != null){
			Toast.makeText(currentActivity,"Eccles encountered issue reading from "+s.getRemoteDevice().getName(),1).show();
		}
		if(!ClassicCompat.isRecepient(this,s.getRemoteDevice().getAddress())) sendIconHeadless(s);
		return true;
	}

	/**
	 * Application-level equivalent of {@code EcclesActivity#sendIcon}, using
	 * {@link #writeHeadless} instead of the Activity-bound {@code write()}/{@code sent()}/
	 * {@code sentFailed()} callback flow, so it works identically regardless of whether any
	 * Activity is currently alive.
	 */
	private void sendIconHeadless(BluetoothSocket socket){
		if(socket == null || !socket.isConnected()){
			Log.w("Eccles","cant send icon to a device not connected");
			return;
		}
		Bitmap b= ClassicCompat.queryDeviceIcon("Eccles",this);
		if(b == null) return;
		EcclesMessage m= new EcclesMessage(EcclesMessage.TYPE_ICON,0)
				.setData(EcclesIcon.convertToBytes(b));
		writeHeadless(socket,m);
	}

	protected void setCurrentActivity(EcclesActivity activity){
		this.currentActivity= activity;

		// adapter is null on devices with no Bluetooth hardware at all (some tablets, TV
		// boxes, emulators). This is called every time any activity becomes current, so an
		// unguarded adapter.isEnabled() here would crash the app immediately on startup on
		// such a device rather than degrading gracefully.
		if(adapter == null) return;

		isOn= adapter.isEnabled();
		if(!isOn && !tryEnable){
			if(pref.getBoolean("auto_enable",false)){
				adapter.enable();
				turnedOn= true;
			} else if(!(activity instanceof EcclesOptions)){
				activity.showDialog("Bluechat need to enable your device bluetooth in order to scan and connect to devices \nyou can ignore this if you want to be offline","Allow","Ignore","Allow Always",()->{

					if(activity.dialog.checked){
						pref.edit().putBoolean("auto_enable",true).apply();
					}

					if(!adapter.enable()){
						activity.enableBtManual();
					}
					turnedOn= true;

				},null);
			}
		}

	}

	public void openDataBase(){
		chatBase= new EcclesData(this,EcclesActivity.DB_CHAT);
		callBase= new EcclesData(this,EcclesActivity.DB_CALL);
		videoBase= new EcclesData(this,EcclesActivity.DB_VIDEO);
		chatStore= new ChatBase(this);
		databaseOpen= true;
	}

	public void closeDataBase(){
		if(chatBase != null){
			chatBase.close();
			chatBase= null;
		}
		if(callBase != null){
			callBase.close();
			callBase= null;
		}
		if(videoBase != null){
			videoBase.close();
			videoBase= null;
		}
		if(chatStore != null){
			chatStore.close();
			chatStore= null;
		}
		databaseOpen= false;
	}

	@Override
	public void onTerminate(){
		if(listener != null){
			listener.cancelListen();
			listener.stopSelf();
		}
		if(reader != null){
			reader.cancelReadingAll();

		}
		if(pref.getBoolean("auto_disable",false)){
			if(adapter != null) adapter.disable();
		} else if(pref.getBoolean("auto_if",false) && turnedOn){
			if(adapter != null) adapter.disable();
		}
		closeDataBase();
		super.onTerminate();
	}
}