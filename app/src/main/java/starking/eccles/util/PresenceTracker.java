package starking.eccles.util;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import starking.eccles.bluechat.bclassic.ClassicCompat;

/**
 * Single source of truth for "is this device running Eccles Bluechat right now, and how near
 * is it?".
 * <p>
 * Why this exists: the old check read {@code BluetoothDevice#getUuids()}, which is only a
 * <i>cached</i> copy of what the device advertised the last time Android happened to look. It
 * is often empty for a device that is running Bluechat and often still full for one that has
 * since closed it - so "Online" flickered or was simply wrong. This class asks the device
 * directly with an SDP lookup ({@code fetchUuidsWithSdp}) and only trusts the fresh answer that
 * comes back in {@code ACTION_UUID}. Lookups run one at a time (Android handles overlapping
 * SDP requests badly) with a timeout, never while a discovery scan is running, and results
 * expire so a stale "Online" can't linger.
 * <p>
 * Signal strength is remembered per device from discovery results, since a paired phone that
 * isn't currently being scanned has no live RSSI of its own.
 */
public final class PresenceTracker {

	private static final String TAG= "PresenceTracker";
	/** An answer older than this is treated as unknown (shown Offline) rather than trusted. */
	private static final long ONLINE_TTL_MS= 45_000;
	/** Signal info older than this is dropped (the device probably moved or left). */
	private static final long SIGNAL_TTL_MS= 90_000;
	/** Don't re-probe a device more often than this unless forced. */
	private static final long MIN_REPROBE_MS= 15_000;
	private static final long PROBE_TIMEOUT_MS= 9_000;

	private static final Map<String,Boolean> online= new HashMap<>();
	private static final Map<String,Long> onlineAt= new HashMap<>();
	private static final Map<String,Integer> signal= new HashMap<>();
	private static final Map<String,Long> signalAt= new HashMap<>();
	private static final Map<String,Long> probedAt= new HashMap<>();

	private static final ArrayDeque<BluetoothDevice> queue= new ArrayDeque<>();
	private static final Handler main= new Handler(Looper.getMainLooper());
	private static BluetoothDevice current;
	private static boolean registered;
	private static Context appContext;
	private static Runnable listener;

	private PresenceTracker(){}

	/** Called by the screen showing device rows so it can redraw when an answer arrives. */
	public static void setListener(Runnable r){
		listener= r;
	}

	// ---------------------------------------------------------------- reading state

	/** True only if a fresh lookup says the device advertises Bluechat. */
	public static boolean isOnline(BluetoothDevice d){
		if(d == null) return false;
		String a= d.getAddress();
		Boolean b= online.get(a);
		Long t= onlineAt.get(a);
		return b != null && b && t != null && System.currentTimeMillis()-t <= ONLINE_TTL_MS;
	}

	/** Marks a device online without a lookup - used when a real connection to it exists. */
	public static void markOnline(String address){
		online.put(address,true);
		onlineAt.put(address,System.currentTimeMillis());
	}

	/** @return 1 (far) .. 4 (very near), or 0 if no recent signal reading exists. */
	public static int signalLevel(String address){
		Integer l= signal.get(address);
		Long t= signalAt.get(address);
		if(l == null || t == null || System.currentTimeMillis()-t > SIGNAL_TTL_MS) return 0;
		return l;
	}

	public static void recordSignal(String address,int level){
		if(address == null || level < 1) return;
		signal.put(address,level);
		signalAt.put(address,System.currentTimeMillis());
	}

	// ---------------------------------------------------------------- probing

	/** Queues a lookup for the given device (skipped if one finished very recently). */
	public static void probe(Context c,BluetoothDevice d,boolean force){
		if(c == null || d == null) return;
		appContext= c.getApplicationContext();
		ensureRegistered();
		Long last= probedAt.get(d.getAddress());
		if(!force && last != null && System.currentTimeMillis()-last < MIN_REPROBE_MS) return;
		for(BluetoothDevice q:queue){
			if(q.getAddress().equals(d.getAddress())) return;
		}
		if(current != null && current.getAddress().equals(d.getAddress())) return;
		queue.add(d);
		next();
	}

	/** Stops pending lookups (e.g. when a discovery scan is about to start). */
	public static void cancelAll(){
		queue.clear();
		main.removeCallbacks(timeout);
		current= null;
	}

	@SuppressLint("MissingPermission")
	private static void next(){
		if(current != null) return;
		BluetoothDevice d= queue.poll();
		if(d == null) return;
		try{
			android.bluetooth.BluetoothAdapter ad= android.bluetooth.BluetoothAdapter.getDefaultAdapter();
			if(ad != null && ad.isDiscovering()){
				// SDP during a scan is slow and unreliable; try again once the scan is over.
				queue.addFirst(d);
				main.postDelayed(PresenceTracker::next,2_000);
				return;
			}
			current= d;
			main.postDelayed(timeout,PROBE_TIMEOUT_MS);
			if(!d.fetchUuidsWithSdp()){
				finish(d,false);
			}
		} catch(Exception e){
			Log.w(TAG,"probe failed to start for "+d.getAddress(),e);
			finish(d,false);
		}
	}

	private static final Runnable timeout= () -> {
		if(current != null){
			finish(current,false);
		}
	};

	private static void finish(BluetoothDevice d,boolean isOnline){
		main.removeCallbacks(timeout);
		String a= d.getAddress();
		online.put(a,isOnline);
		onlineAt.put(a,System.currentTimeMillis());
		probedAt.put(a,System.currentTimeMillis());
		if(current != null && current.getAddress().equals(a)) current= null;
		if(listener != null) listener.run();
		main.postDelayed(PresenceTracker::next,300);
	}

	private static void ensureRegistered(){
		if(registered || appContext == null) return;
		registered= true;
		ContextCompat.registerReceiver(appContext,new BroadcastReceiver(){
			@Override
			public void onReceive(Context context,Intent intent){
				BluetoothDevice d= intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				if(d == null) return;
				Parcelable[] raw= intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
				boolean found= false;
				if(raw != null){
					for(Parcelable p:raw){
						if(p instanceof ParcelUuid && ((ParcelUuid) p).getUuid().equals(ClassicCompat.uuid)){
							found= true;
							break;
						}
					}
				}
				// Android also fires ACTION_UUID for lookups we didn't ask for; only a device
				// that answers positively is acted on in that case, to avoid a stray empty
				// broadcast marking an online device offline.
				boolean ours= current != null && current.getAddress().equals(d.getAddress());
				if(ours || found){
					if(ours) finish(d,found);
					else {
						online.put(d.getAddress(),true);
						onlineAt.put(d.getAddress(),System.currentTimeMillis());
						if(listener != null) listener.run();
					}
				}
			}
		},new IntentFilter(BluetoothDevice.ACTION_UUID),ContextCompat.RECEIVER_EXPORTED);
	}
}
