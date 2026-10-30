package starking.eccles.util;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Persistent record of what we know about each person/device we deal with: the name they
 * announced, the name WE gave them, the last Bluetooth name we saw, and what kind of device
 * they are. Everything that shows a name reads it from here, so names keep working with
 * Bluetooth switched off (the old code asked the live BluetoothDevice every time, which
 * returns nothing when the adapter is off - hence empty names in the Chats/Calls/Video lists).
 * <p>
 * Name priority: the name you set for them &gt; the name they announced over Bluechat &gt; the
 * Bluetooth name we last saw &gt; "Unknown device".
 * <p>
 * Your OWN Bluechat name is separate from the phone's Bluetooth name: it is stored in the app's
 * settings and is never written to the Bluetooth adapter.
 */
public final class PeerStore {

	private static final String FILE= "eccles_peers";
	private static final String PREF_OWN_NAME= "name";
	public static final int MAX_NAME= 30;

	private static Context app;

	private PeerStore(){}

	public static void init(Context c){
		if(c != null) app= c.getApplicationContext();
	}

	private static SharedPreferences sp(){
		return app == null ? null : app.getSharedPreferences(FILE,Context.MODE_PRIVATE);
	}

	// ------------------------------------------------------------------ own profile

	/** The name other people see for you: your Bluechat name, else the phone's Bluetooth name. */
	@SuppressLint("MissingPermission")
	public static String ownName(){
		try{
			if(app != null){
				String n= androidx.preference.PreferenceManager.getDefaultSharedPreferences(app).getString(PREF_OWN_NAME,null);
				if(n != null && n.trim().length() > 0) return n.trim();
			}
		} catch(Exception ignored){}
		try{
			BluetoothAdapter ad= BluetoothAdapter.getDefaultAdapter();
			String bt= ad != null ? ad.getName() : null;
			if(bt != null && bt.trim().length() > 0) return bt.trim();
		} catch(Exception ignored){}
		return Build.MODEL != null ? Build.MODEL : "My phone";
	}

	/** What this build of Bluechat tells peers it is running on. */
	public static String ownPlatform(){
		return "Android";
	}

	/** Payload of the profile message sent once per connection. */
	public static byte[] profilePayload(){
		try{
			JSONObject o= new JSONObject();
			o.put("name",ownName());
			o.put("platform",ownPlatform());
			return o.toString().getBytes(StandardCharsets.UTF_8);
		} catch(Exception e){
			return ownName().getBytes(StandardCharsets.UTF_8);
		}
	}

	// ------------------------------------------------------------------ peers

	/** Stores the name/platform a peer announced. Returns true if something changed. */
	public static boolean saveAnnounced(String address,byte[] payload){
		SharedPreferences p= sp();
		if(p == null || address == null || payload == null) return false;
		String name= null, platform= null;
		try{
			JSONObject o= new JSONObject(new String(payload,StandardCharsets.UTF_8));
			name= o.optString("name",null);
			platform= o.optString("platform",null);
		} catch(Exception e){
			name= new String(payload,StandardCharsets.UTF_8);
		}
		SharedPreferences.Editor ed= p.edit();
		if(name != null && name.trim().length() > 0){
			ed.putString("n_"+address,clip(name));
		}
		if(platform != null && platform.trim().length() > 0){
			ed.putString("p_"+address,clip(platform));
		}
		return ed.commit();
	}

	private static String clip(String s){
		s= s.trim();
		return s.length() > 40 ? s.substring(0,40) : s;
	}

	/** Remembers the Bluetooth name for later, when the adapter may be off. */
	@SuppressLint("MissingPermission")
	public static void rememberBluetoothName(BluetoothDevice d){
		SharedPreferences p= sp();
		if(p == null || d == null) return;
		try{
			String n= d.getName();
			if(n != null && n.trim().length() > 0 && !n.equals(p.getString("b_"+d.getAddress(),null))){
				p.edit().putString("b_"+d.getAddress(),n).apply();
			}
		} catch(Exception ignored){}
	}

	/** Sets the name YOU call this person. Null/blank clears it (back to their own name). */
	public static void setCustomName(String address,String name){
		SharedPreferences p= sp();
		if(p == null || address == null) return;
		if(name == null || name.trim().length() == 0) p.edit().remove("c_"+address).commit();
		else p.edit().putString("c_"+address,clip(name)).commit();
	}

	public static String customName(String address){
		SharedPreferences p= sp();
		return p == null || address == null ? null : p.getString("c_"+address,null);
	}

	public static String displayName(String address){
		return displayName(address,null);
	}

	public static String displayName(BluetoothDevice d){
		return d == null ? "Unknown device" : displayName(d.getAddress(),d);
	}

	@SuppressLint("MissingPermission")
	private static String displayName(String address,BluetoothDevice d){
		SharedPreferences p= sp();
		if(p != null && address != null){
			String v= p.getString("c_"+address,null);
			if(v != null && v.trim().length() > 0) return v;
			v= p.getString("n_"+address,null);
			if(v != null && v.trim().length() > 0) return v;
		}
		String live= null;
		try{
			if(d == null && address != null){
				BluetoothAdapter ad= BluetoothAdapter.getDefaultAdapter();
				if(ad != null) d= ad.getRemoteDevice(address);
			}
			if(d != null) live= d.getName();
		} catch(Exception ignored){}
		if(live != null && live.trim().length() > 0){
			if(p != null && address != null) p.edit().putString("b_"+address,live).apply();
			return live;
		}
		String cached= p != null && address != null ? p.getString("b_"+address,null) : null;
		return cached != null && cached.trim().length() > 0 ? cached : "Unknown device";
	}

	/** The name the person chose for themselves (as opposed to what you call them). */
	public static String announcedName(String address){
		SharedPreferences p= sp();
		return p == null || address == null ? null : p.getString("n_"+address,null);
	}

	/**
	 * A friendly device type: "Android", "iPhone", "PC"... as announced by the peer when known,
	 * otherwise guessed from the Bluetooth device class ("Phone", "Computer", "Audio device"...).
	 */
	public static String deviceType(BluetoothDevice d){
		if(d == null) return "Device";
		SharedPreferences p= sp();
		String announced= p != null ? p.getString("p_"+d.getAddress(),null) : null;
		if(announced != null && announced.trim().length() > 0) return announced;
		try{
			if(PresenceTracker.isOnline(d)) return "Android";   // only Android runs Bluechat so far
			BluetoothClass bc= d.getBluetoothClass();
			if(bc == null) return "Device";
			switch (bc.getMajorDeviceClass()){
				case BluetoothClass.Device.Major.PHONE: return "Phone";
				case BluetoothClass.Device.Major.COMPUTER: return "Computer";
				case BluetoothClass.Device.Major.AUDIO_VIDEO: return "Audio device";
				case BluetoothClass.Device.Major.PERIPHERAL: return "Keyboard or mouse";
				case BluetoothClass.Device.Major.WEARABLE: return "Wearable";
				case BluetoothClass.Device.Major.HEALTH: return "Health device";
				case BluetoothClass.Device.Major.IMAGING: return "Printer or camera";
				case BluetoothClass.Device.Major.TOY: return "Toy";
				case BluetoothClass.Device.Major.NETWORKING: return "Network device";
				default: return "Device";
			}
		} catch(Exception e){
			return "Device";
		}
	}
}
