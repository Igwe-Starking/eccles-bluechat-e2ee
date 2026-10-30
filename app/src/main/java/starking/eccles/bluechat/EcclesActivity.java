package starking.eccles.bluechat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.google.android.material.snackbar.Snackbar;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import starking.eccles.Surface.EcclesAdapter;
import starking.eccles.Surface.EcclesPojo;
import starking.eccles.bluechat.Interface.EcclesWriter;
import starking.eccles.bluechat.activities.HelpActivity;
import starking.eccles.bluechat.activities.InfoActivity;
import starking.eccles.bluechat.activities.PreferenceActivity;
import starking.eccles.bluechat.activities.ViewActivity;
import starking.eccles.bluechat.bclassic.ClassicCompat;
import starking.eccles.bluechat.service.EcclesService;
import starking.eccles.bluechat.service.Listener;
import starking.eccles.bluechat.service.Reader;
import starking.eccles.bluechat.ui.EcclesDialog;
import starking.eccles.bluechat.ui.ProgressDialog;
import starking.eccles.crypto.EccSession;
import starking.eccles.crypto.KeyChangedException;
import starking.eccles.crypto.SessionManager;
import starking.eccles.data.EcclesData;
import starking.eccles.data.EcclesStorage;
import starking.eccles.Interface.EcclesMessage;
import starking.eccles.receivers.BluetoothStateReceiver;
import starking.eccles.util.EcclesIcon;

public class EcclesActivity extends AppCompatActivity {

	public SearchView actionSearch;

	public static final String ALLOW_AUTO_ENABLE_BT= "auto enable bt";
	public static final String PREF_NAME= "Eccles Preference";
	public static final int BLUETOOTH_ENABLE_CODE= 9989;
	public static final int LOCATION_PERM= 223;
	public static final int STORAGE_PERM= 224;
	public static final int BLUETOOTH_PERM= 227;
	public static final int RECORD_PERM= 225;
	public static final int CAMERA_PERM= 226;
	public static final int NOTIFICATION_PERM= 228;
	public static final int DISCOVERABLE_REQUEST_CODE= 9990;
	public static final String DB_CHAT="BluechatChat";
	public static final String DB_CALL="BluechatCall";
	public static final String DB_VIDEO="BluechatVideo";
	public static final int BLOCK_BLOCK= 12;
	public static final int BLOCK_UNBLOCK= 13;
	public static final int BLOCK_CHECK_BLOCK= 14;
	public static final String TAG= "Eccles";

	public EcclesDialog dialog,progressDialog;
	private BluetoothStateReceiver stateReceiver;
	private ExecutorService service,writer;
	public Reader reader;
	private ServiceConnection connection;
	public EcclesData chatBase,callBase,videoBase;
	public EcclesApplication app;
	public Listener listener;
	private View contextView;
	private boolean toasted= false;
	private java.util.function.Consumer<Boolean> discoverableCallback;

	@Override
	protected void onCreate(Bundle b){
		super.onCreate(b);

		app= (EcclesApplication) getApplication();
		applySecureFlag();

		actionSearch = new SearchView(this);

		// The tab Fragments read these straight off the Activity. If the databases aren't open
		// (e.g. this screen was restored directly after the process restarted, skipping the
		// MainActivity splash that normally opens them) every load fails with an NPE on a null
		// EcclesData, so open them here first and always take the live instances.
		if(!app.databaseOpen && app.adapter != null){
			try{ app.openDataBase(); } catch(Exception e){ android.util.Log.e("EcclesActivity","openDataBase failed",e); }
		}
		chatBase= app.chatBase;
		callBase= app.callBase;
		videoBase= app.videoBase;

		reader= app.reader;
		listener= app.listener;
		// setStatusListener(this) used to be called here on every Activity creation - removed
		// along with EcclesActivity's onAccepted()/onFailed()/onActive() implementations; see
		// EcclesApplication#onAccepted and its onServiceConnected() for where this
		// registration now permanently lives instead.
		if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S){
			requestPermission(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},BLUETOOTH_PERM);
		}
		if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU){
			// POST_NOTIFICATIONS is declared in the manifest (required for any notification to
			// show at all on Android 13+), but a manifest declaration alone does not grant a
			// runtime permission - without this request, the app could never even prompt the
			// user for it, and every notification (new message, new connection, in-call,
			// listening-in-background) would be silently suppressed on every Android 13+
			// device with no way for the user to fix it short of finding the setting manually.
			requestPermission(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERM);
		}

	}

	/**
	 * Tracks how many EcclesActivity screens are currently started (visible/resumed) across
	 * the whole app, so {@link #onStop} can detect "the entire app just went to the
	 * background" (count reaches 0) rather than just "this one screen stopped" (e.g. because
	 * the user navigated from one in-app screen to another, which should NOT trigger cleanup).
	 * A lightweight, dependency-free equivalent of AndroidX's ProcessLifecycleOwner.
	 */
	private static int activeActivityCount= 0;

	/** True while any of this app's activities is started (visible) - i.e. the app is in the
	 *  foreground, as opposed to merely alive in the background (which Listener's foreground
	 *  service keeps it even while no screen is visible). Used by
	 *  {@link starking.eccles.util.EcclesNotifier#notifyIncomingCall} to decide whether to just
	 *  jump straight to the call screen (foreground) or post a ringing notification instead
	 *  (background) - see that method's doc for why that split matters. */
	public static boolean isAppInForeground(){
		return activeActivityCount > 0;
	}

	/** Set (when the "Unlock with Fingerprint" preference is on) every time the app goes to the
	 *  background, and cleared once {@link MajorActivity#checkBiometricLock} succeeds - i.e.
	 *  "the app needs a fresh fingerprint check before anything in it should be usable". Starts
	 *  true so a cold start also requires it, matching "just started" from the setting's own
	 *  description. */
	public static boolean needsUnlock= true;

	@Override
	protected void onStart(){
		super.onStart();
		activeActivityCount++;
		applySecureFlag();
		if(isLockable()) enforceBiometricLock();
	}

	/** Whether this screen is covered by the fingerprint lock. Everything is, except the
	 *  call screens (an incoming call must stay answerable) - those override this. */
	protected boolean isLockable(){ return true; }

	/** While the fingerprint lock setting is on, FLAG_SECURE blocks screenshots/screen
	 *  recording and makes the recents/overview thumbnail black. Re-evaluated on every start
	 *  so toggling the setting takes effect without restarting the app. */
	protected void applySecureFlag(){
		try{
			boolean on= app != null && app.pref.getBoolean("biometric_lock",false);
			if(on) getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,android.view.WindowManager.LayoutParams.FLAG_SECURE);
			else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
		} catch(Exception e){ android.util.Log.e("EcclesActivity","secure flag",e); }
	}

	private View lockCover;
	private static final int LOCK_AUTHENTICATORS= androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK | androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL;

	/** Shared lock for activities without their own overlay (ChatActivity). Call from onStart.
	 *  Covers the screen with an opaque view until authentication succeeds. */
	private boolean lockPromptShowing= false;

	protected void enforceBiometricLock(){
		if(lockPromptShowing && needsUnlock) return;
		if(!needsUnlock || !app.pref.getBoolean("biometric_lock",false)){
			if(lockCover != null) lockCover.setVisibility(View.GONE);
			return;
		}
		if(lockCover == null){
			TextView t= new TextView(this);
			t.setText("Locked - tap to unlock");
			t.setTextColor(0xffffffff);
			t.setGravity(android.view.Gravity.CENTER);
			t.setBackgroundColor(0xff000000);
			t.setClickable(true);
			// An explicit tap always retries, even if a stale "prompt showing" flag survived.
			t.setOnClickListener(v-> { lockPromptShowing= false; enforceBiometricLock(); });
			lockCover= t;
			// On the decor view (not android.R.id.content) so it also covers the toolbar and
			// option menu - otherwise Settings was reachable, and the lock switchable off, while locked.
			((ViewGroup) getWindow().getDecorView()).addView(t,new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
		}
		lockCover.setVisibility(View.VISIBLE);
		lockCover.bringToFront();
		lockPromptShowing= true;

		if(androidx.biometric.BiometricManager.from(this).canAuthenticate(LOCK_AUTHENTICATORS) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS){
			needsUnlock= false;
			lockPromptShowing= false;
			lockCover.setVisibility(View.GONE);
			return;
		}
		androidx.biometric.BiometricPrompt.PromptInfo info= new androidx.biometric.BiometricPrompt.PromptInfo.Builder()
				.setTitle("Unlock Bluechat")
				.setSubtitle("Confirm your fingerprint or device PIN/pattern/password to continue")
				.setAllowedAuthenticators(LOCK_AUTHENTICATORS)
				.build();
		new androidx.biometric.BiometricPrompt(this,androidx.core.content.ContextCompat.getMainExecutor(this),
				new androidx.biometric.BiometricPrompt.AuthenticationCallback(){
					@Override
					public void onAuthenticationSucceeded(androidx.biometric.BiometricPrompt.AuthenticationResult r){
						super.onAuthenticationSucceeded(r);
						needsUnlock= false;
						lockPromptShowing= false;
						lockCover.setVisibility(View.GONE);
					}
					@Override
					public void onAuthenticationError(int code,CharSequence msg){
						super.onAuthenticationError(code,msg);
						lockPromptShowing= false;
						// ERROR_CANCELED = the system cancelled the prompt (e.g. rotation recreated
						// the screen); the user didn't refuse, so don't send the app to the background.
						if(code == androidx.biometric.BiometricPrompt.ERROR_CANCELED || isFinishing() || isChangingConfigurations()) return;
						moveTaskToBack(true);
					}
				}).authenticate(info);
	}

	@Override
	protected void onStop(){
		super.onStop();
		// A BiometricPrompt that was up when the app was backgrounded is torn down silently
		// (no callback), so this flag would stay true forever and every later attempt to
		// unlock - including tapping the lock screen - would be ignored. Always reset it here.
		lockPromptShowing= false;
		activeActivityCount--;
		// A rotation (or other configuration change) stops this Activity only to recreate it
		// straight away - the app is NOT going to the background, so it must not clear the
		// plaintext cache or require a fresh unlock.
		if(isChangingConfigurations()) return;
		if(activeActivityCount <= 0){
			activeActivityCount= 0;
			// The whole app (not just this one screen) has gone to the background. Decrypted
			// media previously stayed in the plaintext cache directory until the next full
			// app cold-start (EcclesApplication#onCreate() was the only place this was ever
			// cleared) - a crash, force-stop, or forensic acquisition of the device before
			// that next restart could leave plaintext photos/videos/voice notes readable on
			// disk indefinitely. Clearing here narrows that exposure window to "until the app
			// is backgrounded" instead. Deleting immediately after each individual use isn't
			// viable here: ChatAdapter's RecyclerView rows re-read the same cached file every
			// time a media message scrolls back into view, so the file needs to persist for as
			// long as the user might still be looking at this screen.
			EcclesStorage.clearPlaintextCache(this);
			if(app != null && app.pref.getBoolean("biometric_lock",false)){
				needsUnlock= true;
			}
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu m){
		m.add("App Info");
		m.add("Settings");
		m.add("help");
		m.add("Exit");

		MenuItem search= m.add("search");

		actionSearch.setQueryHint("Search device");

		search.setActionView(actionSearch);
		search.setIcon(android.R.drawable.ic_menu_search);
		search.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

		MenuItem visib= m.add("Visibility Change");
		visib.setIcon(android.R.drawable.ic_menu_view);
		visib.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		return true;
	}

	public void showDialog(String m,String pb,String nb,String nub,Runnable pa,Runnable na){
		try{
		if(!app.pref.getBoolean("no_alert",false)){

		dialog= new EcclesDialog(m,pb,nb,nub,pa,na);
		dialog.show(getSupportFragmentManager(),"EcclesDialog");
		}
		} catch (Exception e){
			Toast.makeText(this,m,1).show();
		}
	}

	public void showProgressDialog(String m){
		try{
		progressDialog= new ProgressDialog(m);
		progressDialog.show(getSupportFragmentManager(),"progressDialog");
		} catch(Exception e){
			Toast.makeText(this,m+"...",1).show();
		}
	}

	public Snackbar showSnack(String sm,String sbt,int sbc,int sd,View v,Runnable sa){
		final Snackbar sb= Snackbar.make(v,sm,sd);
		sb.setAction(sbt,(View vv)->{
			if(sa != null) sa.run();
			sb.dismiss();
		});
		sb.setActionTextColor(sbc);
		sb.setBackgroundTint(0xFF2B2640);
		sb.setTextColor(Color.WHITE);

		sb.show();
		return sb;
	}

	@Override
	public void onCreateContextMenu(ContextMenu m,View v,ContextMenu.ContextMenuInfo info){
		String address= v.getTag().toString();

			contextView= v;
			m.add(((EcclesApplication)getApplication()).isConnected(address) ? "Disconnect":"Connect");
			m.add("Rename");
			m.add("Delete");
			m.add(block(address,BLOCK_CHECK_BLOCK)?"Unblock":"Block");

	}

	@Override
	public void onResume(){
		super.onResume();
		app.setCurrentActivity(this);
	}

	@Override
	public void onPause(){
		if(app.currentActivity == this) app.currentActivity = null;
		super.onPause();
	}

	public void enableBtManual(){
		ActivityCompat.startActivityForResult(this,new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),BLUETOOTH_ENABLE_CODE,null);
	}

	public boolean requestPermission(String[] p,int rc){

		boolean allGranted= true;
		for(String perm: p){
			if(ActivityCompat.checkSelfPermission(this,perm) != PackageManager.PERMISSION_GRANTED){
				allGranted= false;
				break;
			}
		}
		if(allGranted){
			return true;
		} else {
			ActivityCompat.requestPermissions(this,p,rc);
		}

		return false;
	}

	@Override
	public void onRequestPermissionsResult(int rc,String[] perm,int[] gr){
		try{
		if(rc== LOCATION_PERM && (gr.length == 0 || gr[0] != PackageManager.PERMISSION_GRANTED)){
			showDialog("Bluechat needs location permission to scan for nearby devices.\nIt is only used to find devices; Bluechat never accesses your location.\nYou can ignore this if you don't want to scan for devices.","Try Again","ignore",null,()->{
				requestPermission(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},LOCATION_PERM);
			},null);
		} else {
			if(this instanceof SelectActivity){
				((SelectActivity)this).startScan();
			}
		}

		if(rc == RECORD_PERM && (gr.length == 0 || gr[0] != PackageManager.PERMISSION_GRANTED)){
			showDialog("Bluechat needs microphone permission to record audio and make voice calls.\nYou can ignore this if you don't want calls and voice notes.","Grant","Ignore",null,()->{
				requestPermission(new String[]{Manifest.permission.RECORD_AUDIO},RECORD_PERM);
			},null);
		}
		} catch (Exception e){
			Log.e("Eccles","error while requesting",e);
		}
	}

	/**
	 * Attempts to make this device Bluetooth-discoverable.
	 * <p>
	 * First tries the hidden {@code BluetoothAdapter.setScanMode()} method via reflection. When
	 * it works, this can silently make the device discoverable with no user-facing interruption
	 * at all. Since Android 9 (API 28), reflective access to hidden/non-SDK platform APIs like
	 * this is blocked by default for apps targeting a recent SDK, so on most current devices
	 * this attempt will fail - but it still works on some low-end/older devices and OEM builds
	 * where that restriction doesn't apply, and trying it first costs nothing (it fails fast).
	 * <p>
	 * If the reflective attempt fails, this falls back to the standard, public
	 * {@code ACTION_REQUEST_DISCOVERABLE} flow, which always works but necessarily shows the
	 * user a system consent dialog (Android does not allow silently requesting this via public
	 * API). {@code callback} is invoked with {@code true} once discoverability is granted,
	 * either way.
	 * <p>
	 * There is no public API to programmatically turn discoverability back off once granted;
	 * it simply expires on its own after the requested duration. See {@link #tryHideFromScans}
	 * for the best-effort reflective attempt at the reverse direction.
	 */
	public void requestDiscoverable(int durationSeconds, java.util.function.Consumer<Boolean> callback){
		if(tryReflectiveSetScanMode(true)){
			if(callback != null) callback.accept(true);
			return;
		}
		this.discoverableCallback= callback;
		Intent intent= new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
		intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,durationSeconds);
		ActivityCompat.startActivityForResult(this,intent,DISCOVERABLE_REQUEST_CODE,null);
	}

	/**
	 * Best-effort attempt to turn discoverability back off via the same hidden reflective API
	 * as {@link #requestDiscoverable}. Unlike the "turn on" direction, there is no public API
	 * fallback here at all - Android provides third-party apps no supported way to revoke
	 * discoverability once granted. Returns false if the reflective call is blocked (the
	 * overwhelming majority of current devices); callers must treat that as "will expire on its
	 * own after its timeout", not as an error to retry.
	 */
	public boolean tryHideFromScans(){
		return tryReflectiveSetScanMode(false);
	}

	private boolean tryReflectiveSetScanMode(boolean visible){
		try{
			BluetoothAdapter adapter= ((EcclesApplication)getApplication()).adapter;
			Method method= adapter.getClass().getMethod("setScanMode",new Class[]{int.class});
			int visib= visible ? BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:BluetoothAdapter.SCAN_MODE_NONE;
			return (boolean)method.invoke(adapter,new Object[]{visib});
		} catch (Exception e){
			Log.w("Eccles","reflective setScanMode("+visible+") unavailable on this device/OS version",e);
			return false;
		}
	}

	@Override
	protected void onActivityResult(int requestCode,int resultCode,Intent data){
		super.onActivityResult(requestCode,resultCode,data);
		if(requestCode == DISCOVERABLE_REQUEST_CODE){
			boolean granted= resultCode != Activity.RESULT_CANCELED;
			java.util.function.Consumer<Boolean> callback= discoverableCallback;
			discoverableCallback= null;
			if(callback != null) callback.accept(granted);
		}
	}

	public boolean isVisible(){
		return ((EcclesApplication)getApplication()).adapter.getScanMode()==BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE;
	}

	public void state(boolean on){
		if(!on){
			toasted= false;

		} else {
			if(listener != null && !listener.disabled && app.pref.getBoolean("auto_listen",true))listen();
			Toast.makeText(this,"Bluetooth turned on",1).show();
			toasted= true;
		}
	}

	@SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device, boolean showProgress){
		try{
		if(performBond(device)){
			BluetoothAdapter adapter= app.adapter;
			if(!adapter.isEnabled()){
				Toast.makeText(this,"Can't connect while Bluetooth is off",1).show();
				return;
			}
			final Handler handler= new Handler(getMainLooper());

			if(service != null){
				service.shutdownNow();
				service= null;
			}
			if(adapter.isDiscovering()) adapter.cancelDiscovery();

			service= Executors.newSingleThreadExecutor();
			service.execute(()->{
				try{
					if(showProgress){
						handler.post(()->{
							showProgressDialog("Connecting to "+starking.eccles.util.PeerStore.displayName(device));
						});
					}
					@SuppressLint("MissingPermission") final BluetoothSocket socket= device.createRfcommSocketToServiceRecord(ClassicCompat.uuid);
					socket.connect();
					handler.post(()->{
						onConnected(socket);
					});
				} catch (Exception e){
					handler.post(()->{
						onConnectionFailed(device,e.getMessage());
					});
				}
			});
		}
		} catch(Exception e){ android.util.Log.e("EcclesActivity", "Suppressed exception", e); }
	}

	public boolean performBond(BluetoothDevice device){
		if(device.getBondState()==BluetoothDevice.BOND_BONDED) return true;

		showDialog("You are not paired with "+starking.eccles.util.PeerStore.displayName(device)+"\nBluechat needs to pair with "+starking.eccles.util.PeerStore.displayName(device)+"in order to create a reliable connection","Pair","Cancel",null,()->{
			if(device.createBond()){

				Toast.makeText(this,"Pairing with "+starking.eccles.util.PeerStore.displayName(device)+"...",1).show();
			} else {
				Toast.makeText(this,"Failed to pair with "+starking.eccles.util.PeerStore.displayName(device),1).show();
			}
		},null);
		return false;
	}

	@CallSuper
	public void onConnected(BluetoothSocket socket){
		app.connectionList.putIfAbsent(socket.getRemoteDevice().getAddress(),socket);
		if(reader == null) reader= app.reader;
		boolean readOk= false;
		for(int i= 0;i<10;i++){
			if(reader.read(socket)){ readOk= true; break; }
		}
		if(!readOk){
			Toast.makeText(this,"Bluechat had a problem reading from "+starking.eccles.util.PeerStore.displayName(socket.getRemoteDevice()),1).show();
		}
		try{
		if(progressDialog !=  null) progressDialog.cancel();
		Toast.makeText(this,"Connected to "+starking.eccles.util.PeerStore.displayName(socket.getRemoteDevice()),1).show();
		if(socket.isConnected()) write(socket,new EcclesMessage(EcclesMessage.TYPE_PROFILE,0).setData(starking.eccles.util.PeerStore.profilePayload()));
		if(!ClassicCompat.isRecepient(this,socket.getRemoteDevice().getAddress()))sendIcon(socket);
		} catch (Exception e){ android.util.Log.e("EcclesActivity", "Suppressed exception", e); }
	}

	// onAccepted/onFailed/onActive (the Listener.ServerStatus implementation) used to live
	// here, re-registered on every Activity's onCreate()/onResume(). That registration went
	// stale once every Activity was destroyed while the app was backgrounded (nothing ever
	// cleared it), leaking the last-destroyed Activity and leaving background connection
	// acceptance depending on a stale object. This logic now lives permanently on
	// EcclesApplication instead (see EcclesApplication#onAccepted and the registration in its
	// onServiceConnected()), which never goes stale. See also that class's sendIconHeadless()
	// for the Activity-independent equivalent of this class's own sendIcon() below.

	public void onConnectionFailed(BluetoothDevice device,String reason){
		try{
		if(progressDialog != null) progressDialog.cancel();
		showDialog("Failed to connect to "+starking.eccles.util.PeerStore.displayName(device)+"\nReason: "+reason,"Try Again","Forget",null,()->{
			connect(device,true);
		},null);
		} catch (Exception e){
			Log.e("Eccles","Dialog show error",e);
		}
	}

	public void onIdentityKeyChanged(BluetoothSocket socket,KeyChangedException ke){
		try{
			if(progressDialog != null) progressDialog.cancel();
		} catch(Exception ignored){}
		String deviceLabel;
		try{ deviceLabel= starking.eccles.util.PeerStore.displayName(socket.getRemoteDevice()); } catch(Exception e){ deviceLabel= "this device"; }
		String fp= formatFingerprint(ke.newFingerprint);
		String msg= "The security key for "+deviceLabel+" has changed since your last conversation.\n\n"+
			"New key:\n"+fp+"\n\n"+
			"This can happen if the other person reinstalled Eccles or reset their device, "+
			"but it can also mean someone is trying to intercept your messages.\n\n"+
			"Do not continue unless you have verified this key with them through another channel.";
		dialog= new EcclesDialog(msg,"Trust New Key","Cancel Connection",null,()->{
			SessionManager.get(this).forceTrustAndRetry(socket,ke.session,ke.newFingerprint,ke.remoteAddress);
			// The reader loop that discovered this key change already exited (KeyChangedException
			// aborts that read() task) by the time the user has a chance to respond to this
			// dialog - forceTrustAndRetry() only installs the session, it doesn't resume
			// reading. Without this call, the connection would stay silently unusable even
			// after the user explicitly chose to trust the new key.
			if(reader != null) reader.read(socket);
		},()->{
			onDisconnected(socket.getRemoteDevice());
		});
		dialog.show(getSupportFragmentManager(),"EcclesKeyChangeDialog");
	}

	private static String formatFingerprint(byte[] fp){
		StringBuilder sb= new StringBuilder();
		for(int i= 0;i<fp.length;i++){
			sb.append(String.format("%02X",fp[i]));
			if(i<fp.length-1 && i%2==1) sb.append(' ');
		}
		return sb.toString();
	}

	public void write(BluetoothSocket socket,EcclesMessage messenger){
		if(writer == null)writer= Executors.newSingleThreadExecutor();
		final Handler handler= new Handler(getMainLooper());
		if(socket != null && messenger != null){
			writer.execute(()->{
				try{
					handler.post(()->{
						sending(socket,messenger);
					});
					EccSession session= SessionManager.get(this).obtain(socket);
					EcclesWriter ot= new EcclesWriter(socket.getOutputStream(),session);
					if(messenger.send(ot)){
					handler.post(()->{
						sent(socket,messenger);
					});
					} else {
						handler.post(()->{
							sentFailed(socket,messenger,"failed to send msg");
						});
					}
				} catch(KeyChangedException ke){
					handler.post(()-> onIdentityKeyChanged(socket,ke));
				} catch(IOException ie){
					onDisconnected(socket.getRemoteDevice());
				} catch(Exception e){
					Log.e("Eccles","failed to sent message",e);
					handler.post(()->{
						sentFailed(socket,messenger,e.toString());
					});

				}
			});
		}
	}
	@CallSuper
	public void sent(BluetoothSocket socket,EcclesMessage messenger){
		if(messenger.type== EcclesMessage.TYPE_ICON){
			ClassicCompat.putRecipient(this,socket.getRemoteDevice().getAddress());
		}
	}
	public void sending(BluetoothSocket socket,EcclesMessage messenger){

	}
	public void sentFailed(BluetoothSocket socket,EcclesMessage messenger,String reason){
		onDisconnected(socket.getRemoteDevice());
	}

	/** Called when a connected peer announces (or changes) its name/platform, so open screens
	 *  can refresh. Default does nothing. */
	public void onPeerProfileChanged(String address){
	}

	/** Lets the user choose what THEY call this person. It only changes how the name looks on
	 *  this phone; the other person's own name for themselves is untouched and can be restored. */
	public void renamePeer(final BluetoothDevice dv){
		if(dv == null) return;
		final android.widget.EditText input= new android.widget.EditText(this);
		input.setSingleLine(true);
		input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(starking.eccles.util.PeerStore.MAX_NAME)});
		input.setText(starking.eccles.util.PeerStore.displayName(dv));
		input.setSelectAllOnFocus(true);
		final String announced= starking.eccles.util.PeerStore.announcedName(dv.getAddress());
		AlertDialog.Builder b= new AlertDialog.Builder(this)
				.setTitle("Rename contact")
				.setMessage("This name is only shown on your phone.")
				.setView(input)
				.setPositiveButton("Save",(d,w)->{
					starking.eccles.util.PeerStore.setCustomName(dv.getAddress(),input.getText().toString());
					onPeerProfileChanged(dv.getAddress());
				})
				.setNegativeButton("Cancel",null);
		if(starking.eccles.util.PeerStore.customName(dv.getAddress()) != null){
			b.setNeutralButton(announced != null ? "Use their name" : "Reset",(d,w)->{
				starking.eccles.util.PeerStore.setCustomName(dv.getAddress(),null);
				onPeerProfileChanged(dv.getAddress());
			});
		}
		b.show();
	}

	public void sendIcon(BluetoothSocket socket){
		if(socket== null || !socket.isConnected()){
			Log.w("Eccles","cant send icon to a device not connected");
			return;
		}

		Bitmap b= ClassicCompat.queryDeviceIcon("Eccles",this);
		if(b == null) return;

		EcclesMessage m= new EcclesMessage(EcclesMessage.TYPE_ICON,0).setData(EcclesIcon.prepareIcon(b));
		write(socket,m);
	}

	public void updateStatus(String status){
		int color= 0;
		switch (status){
			case "active":
				color= 0xFF9BF2C4;
				break;
			case "offline":
				color= 0xFFFFB4AB;
				break;
			case "available":
				color= 0xFFB9D3FF;
				break;
			default:
				return;
		}

		SpannableString sp= new SpannableString(status);
		sp.setSpan(new ForegroundColorSpan(color),0,sp.length(),0);

		ActionBar bar= getSupportActionBar();
		if(bar != null){
			bar.setSubtitle(sp);
		}
		bar= null;
	}

	@Override
	public boolean onContextItemSelected(MenuItem item){
		if(contextView != null){
			String tg= (String) contextView.getTag();
			BluetoothDevice dv= app.adapter.getRemoteDevice(tg);
			if(tg != null){
				switch (item.getTitle().toString()){
					case "Connect":
						connect(dv,true);
						return true;
					case "Disconnect":
						disconnect(dv,true);
						return true;
					case "Rename":
						renamePeer(dv);
						return true;
					case "Delete":
						if(this instanceof SelectActivity)delete(dv,true);else delete(dv,false);
						ClassicCompat.deleteDeviceIcon(dv.getAddress(),this);
						return true;
					case "Block":
						showDialog("Are you sure you want to block "+starking.eccles.util.PeerStore.displayName(app.adapter.getRemoteDevice(tg))+"\nThis device can still discover and connect to you, but it will no longer be able to chat with you, call you or video call you.","Block","Cancel",null,()->{
							block(tg,BLOCK_BLOCK);
						},null);
						return true;
					case "Unblock":
						block(tg,BLOCK_UNBLOCK);
						return true;
				}
			}
		}
		return false;
	}

	public void disconnect(final BluetoothDevice d,boolean warn){
		if(warn){
			// onDisconnected() invalidates this device's E2EE session and removes it from
			// app.connectionList - it must only run if the user actually confirms, not
			// unconditionally after merely showing the dialog. Previously this fired
			// immediately regardless of the user's choice, so cancelling "disconnect?" still
			// silently discarded the live session/crypto state while leaving the socket open.
			showDialog("Are you sure you want to disconnect from "+starking.eccles.util.PeerStore.displayName(d)+"\nAny pending transactions will be cancelled.","Disconnect","Cancel",null,()->{
				close(d);
				onDisconnected(d);
			},null);
		} else {
			close(d);
			onDisconnected(d);
		}
	}

	private final void close(BluetoothDevice d){
		BluetoothSocket s= app.getSocket(d.getAddress());
		if(s != null){
			app.connectionList.remove(d.getAddress());
			try{
			s.close();
			} catch(Exception e){
				Log.e("Eccles","failed to close socket",e);
			}
		}
	}

	public void delete(final BluetoothDevice d,boolean unpair){
		if(unpair){
			if(d.getBondState()==BluetoothDevice.BOND_BONDED){
				showDialog("Deleting "+starking.eccles.util.PeerStore.displayName(d)+" will remove it from your paired devices.\nDo you want to continue?","Delete","Cancel",null,()->{
					if(unPair(d)){
						Toast.makeText(this,starking.eccles.util.PeerStore.displayName(d)+" unpaired",1).show();
					} else {
						Toast.makeText(this,"Failed to unpair "+starking.eccles.util.PeerStore.displayName(d),1).show();
					}
					remove(d);
				},null);
			}
			return;
		}
		remove(d);
	}

	private final void remove(BluetoothDevice d){
		if(this instanceof SelectActivity){
			SelectActivity sel= (SelectActivity) this;
			EcclesAdapter ad= sel.pairedAdapter;
			EcclesPojo pj= ad.findPojoWithTag(d.getAddress());
			if(pj != null){
				ad.pojos.remove(pj);
				ad.notifyDataSetChanged();
			}
		}
	}

	public boolean block(String add,int st){
		SharedPreferences pref= getSharedPreferences("Blocked_list",MODE_PRIVATE);
		switch (st){
			case BLOCK_BLOCK:
				pref.edit().putBoolean(add,true).apply();
				Toast.makeText(this,starking.eccles.util.PeerStore.displayName(app.adapter.getRemoteDevice(add))+" blocked",1).show();
				break;
			case BLOCK_UNBLOCK:
				pref.edit().remove(add).apply();
				Toast.makeText(this,starking.eccles.util.PeerStore.displayName(app.adapter.getRemoteDevice(add))+" unblocked",1).show();
				break;
			case BLOCK_CHECK_BLOCK:
				return pref.getBoolean(add,false);
		}
		return false;
	}

	/**
	 * Best-effort unpair via the hidden {@code BluetoothDevice.removeBond()} method. Unlike
	 * {@link #requestDiscoverable}, Android provides no public API for a third-party app to
	 * unpair a device at all, so there is no fallback available here if this reflective call
	 * is blocked by the device/OS's non-SDK-interface restrictions (see
	 * {@link #tryReflectiveSetScanMode} for the same situation on the discoverability side,
	 * where a public fallback does exist). Callers already handle a {@code false} result
	 * gracefully.
	 */
	protected final boolean unPair(BluetoothDevice d){
		try{
			Method m= d.getClass().getMethod("removeBond",new Class[]{});
			return (boolean) m.invoke(d,new Object[]{});
		} catch (Exception e){
			Log.e("Eccles","unpaire failed",e);
			return false;
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem it){
		switch (it.getTitle().toString()){
			case "App Info":
				startActivity(new Intent(this,InfoActivity.class));
				return true;
			case "Settings":
				startActivity(new Intent(this,PreferenceActivity.class));
				return true;
			case "help":
				startActivity(new Intent(this,HelpActivity.class));
				return true;
			case "Exit":
				exit(true);
				return true;
		}
		return false;
	}

	public void exit(boolean alert){

		if(alert){
			showDialog("Are you sure you want to exit?\nAll current transactions will be cancelled.","Exit","Cancel",null,()->{
				exit(false);
			},null);
			return;
		}
		app.onTerminate();
		ActivityCompat.finishAffinity(this);
	}

	public void view(Uri u,int t){
		Intent vint= new Intent(this,ViewActivity.class);
		vint.putExtra("type",t);
		vint.setData(u);
		startActivity(vint);
	}

	public void onDisconnected(BluetoothDevice device){
		BluetoothSocket s= app.connectionList.remove(device.getAddress());
		if(s != null) SessionManager.get(this).invalidate(s);
	}

	// The read(BluetoothSocket) wrapper (10x retry over Reader#read) that used to live here was
	// only ever called from the now-removed onAccepted() above (that retry loop now lives in
	// EcclesApplication#onAccepted); onConnected() above inlines the same retry loop directly
	// over reader.read() since it is still called from here.

	/**
	 * Gate for premium/"Pro"-only actions. Callers use the pattern
	 * {@code if(checkPro(r)) r.run();} - so this method must only report whether the action is
	 * allowed, and must never invoke {@code r} itself, or the action runs twice (once here, once
	 * in the caller). There is currently no Pro/premium tier implemented, so every action is
	 * allowed.
	 */
	public final boolean checkPro(Runnable r){
		return true;
	}

	public void listen(){
		listener.listen();
		new Handler(android.os.Looper.getMainLooper()).postDelayed(()->{
			if(!listener.listening){
				listener.listen();
			}
		},10000);
	}
}
