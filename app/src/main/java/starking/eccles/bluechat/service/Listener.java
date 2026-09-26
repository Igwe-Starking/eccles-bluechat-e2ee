package starking.eccles.bluechat.service;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Process;
import android.util.Log;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import starking.eccles.bluechat.EcclesActivity;
import starking.eccles.bluechat.EcclesApplication;
import starking.eccles.bluechat.bclassic.ClassicCompat;
import starking.eccles.util.EcclesNotifier;

public class Listener extends EcclesService {

	private BluetoothServerSocket serverSocket;
	private ServerStatus status;
	private ExecutorService worker;
	private Handler handler;
	public boolean listening;
	public boolean disabled;

	/**
	 * Promotes this service to a foreground service immediately on creation, with a persistent
	 * low-priority notification (see {@link EcclesNotifier#notifyListening}).
	 * <p>
	 * Without this, Listener was an ordinary background Service with no persistent notification,
	 * and Android's background execution limits mean the OS can and will kill it after the app
	 * is backgrounded for a while - silently stopping incoming connections/messages from being
	 * received until the app is reopened. This is called here, in onCreate(), rather than
	 * later inside {@link #listen()} (which is invoked externally, e.g. from MajorActivity
	 * after binding to this service) because Android requires startForeground() to be called
	 * promptly after the service is started via startForegroundService() - deferring it until
	 * some external caller gets around to invoking listen() risked missing that window
	 * (ForegroundServiceDidNotStartInTimeException on Android 12+). Requires
	 * android:foregroundServiceType="connectedDevice" plus the
	 * FOREGROUND_SERVICE_CONNECTED_DEVICE permission (see AndroidManifest.xml).
	 */
	@Override
	public void onCreate(){
		super.onCreate();
		startForeground(EcclesNotifier.LISTENING_NOTIFICATION_ID,EcclesNotifier.notifyListening(this));
	}

	public interface ServerStatus {

		public void onActive();
		/** @return true if the connection was actually accepted, false if it was rejected
		 *  (unpaired/blocked peer, bond-verification failure) - see the call site in
		 *  {@link #listen}, which only shows a "connected" notification when this is true. */
		public boolean onAccepted(BluetoothSocket socket);
		public void onFailed(String r);
	}

	public void setStatusListener(ServerStatus status){
		this.status= status;
	}

	public void listen(){
		if(handler== null) handler= new Handler(android.os.Looper.getMainLooper());
		if(worker == null){
			// Re-promotes to foreground in case a prior cancelListen() dropped it (see that
			// method and onCreate()'s doc) - calling startForeground() again here is safe/
			// idempotent (it just updates the existing notification) and is needed for the
			// "user turned Visibility off, then back on" case to stay protected from being
			// killed in the background.
			startForeground(EcclesNotifier.LISTENING_NOTIFICATION_ID,EcclesNotifier.notifyListening(this));
			worker= Executors.newSingleThreadExecutor();

			worker.execute(()->{
				BluetoothAdapter adapter= ((EcclesApplication) getApplication()).adapter;

				try{
				serverSocket= adapter.listenUsingRfcommWithServiceRecord("Eccles",ClassicCompat.uuid);
				} catch(Exception e){
					if(status != null) status.onFailed(e.getMessage());
					cancelListen();
					return;
				}

				if(serverSocket == null){
					if(status != null) status.onFailed("Failed to start server");
					cancelListen();
					return;
				}
				try{
					Thread.sleep(200);
				} catch (InterruptedException i){ android.util.Log.e("Listener", "Suppressed exception", i); }
				while (adapter.isEnabled()){
					try{
						if(status != null) status.onActive();
						listening= true;
						disabled= false;
						final BluetoothSocket socket= serverSocket.accept();
						if(socket != null){
							handler.post(()->{
								if(status != null && status.onAccepted(socket)){
									EcclesNotifier.notifyConnect(this,socket.getRemoteDevice());
								}
							});
							EcclesApplication ea= (EcclesApplication) getApplication();

							if(!ea.pref.getBoolean("allow_multiple",true)){
								cancelListen();
							} else if(Integer.parseInt(ea.pref.getString("num_con","0")) == ea.connectionList.size()){
								cancelListen();
							}
						}
					} catch (Exception e){
						listening= false;
						Log.e("Eccles","error encountered while listening",e);
						if(status != null) status.onFailed(e.getMessage());
						cancelListen();
						break;
					}
				}
				if(status != null) status.onFailed("Bluetooth is Off");
				cancelListen();
			});
		}
	}

	public void cancelListen(){
		listening= false;
		stopForeground(STOP_FOREGROUND_REMOVE);
		if(serverSocket != null){
			// accept() below is a blocking call that worker.shutdown() alone cannot interrupt
			// (shutdown() only stops new tasks from being submitted, it doesn't touch an
			// already-running one). Without closing the socket here, a thread blocked in
			// accept() stayed alive and would silently accept a new incoming connection even
			// after cancelListen() was called - meaning turning off "Visibility" in the UI
			// didn't actually stop new connections from being accepted in the background.
			// Closing it here forces that blocked accept() to throw, which the loop's own
			// catch block already handles by breaking out cleanly.
			try{
				serverSocket.close();
			} catch (Exception e){ Log.e("Eccles","failed to close listening server socket",e); }
			serverSocket= null;
		}
		if(worker != null){
			worker.shutdown();
			worker= null;
		}
	}

}