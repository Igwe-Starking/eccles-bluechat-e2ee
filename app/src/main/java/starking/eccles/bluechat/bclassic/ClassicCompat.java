package starking.eccles.bluechat.bclassic;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.ParcelUuid;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import starking.eccles.bluechat.EcclesActivity;
import starking.eccles.bluechat.EcclesApplication;
import starking.eccles.bluechat.activities.ViewActivity;
import starking.eccles.bluechat.service.Listener;
import starking.eccles.crypto.KeystoreAes;
import starking.eccles.util.EcclesIcon;
import starking.eccles.bluechat.R;

public class ClassicCompat {

	public static final String DEVICE_ICON_STORAGE="starkin.eccles.bluechat.deviceIcons";
	public static final String ICON_RECEPIENTS="starking.eccles.bluechat.recepients";
	private static final String DEVICE_ADDRESS="Eccles_Address";
	private static final String DEVICE_UNREAD_STORE= "starking.eccles.bluechat.UNREAD";
	private static final String TONE_STORE= "EcclesTone.mp3";
	/**
	 * Canonical RFCOMM service UUID for the Eccles Bluechat protocol. This MUST be the single
	 * source of truth used by every socket-listen / socket-connect / device-capability-check
	 * call site in the app, including {@link EcclesActivity} and {@link Listener}, so that
	 * {@link #checkOnline(BluetoothDevice)} can reliably find the service UUID being advertised.
	 */
	public static final String SERVICE_NAME_SEED= "Ecclesiastes";
	public static final UUID uuid= UUID.nameUUIDFromBytes(SERVICE_NAME_SEED.getBytes(StandardCharsets.UTF_8));

	public static boolean queryDeviceType(BluetoothDevice device){
		return device.getBluetoothClass().getDeviceClass()== 524;
	}

	public static int queryDeviceRSSI(Intent intent){
		short rssi=intent.getShortExtra(BluetoothDevice.EXTRA_RSSI,Short.MIN_VALUE);

		if(!queryDeviceType(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE))){
			return -1;
		}

		if(rssi > -50){
			return 4;
		} else if(rssi > -70){
			return 3;
		} else if(rssi > -80){
			return 2;
		} else if(rssi > -90){
			return 1;
		} else {
			return -1;
		}
	}

	public static String queryDistance(int p){
		switch (p){
			case 1:
				return "Approx 9 - 10 meters away";
			case 2:
				return "Approx 6-8 meters away";
			case 3:
				return "Approx 4-6 meters away";
			case 4:
				return "Approx 1-3 meters away";
			default:
				return null;
		}
	}

	/**
	 * Dedicated at-rest encryption key for profile icon bytes stored in SharedPreferences - a
	 * separate Keystore alias from ChatBase's message-payload key, so a compromise of one
	 * doesn't affect the other's data. Profile icons come from a peer over the app's E2EE
	 * transport and are encrypted before being written to SharedPreferences, so reading the
	 * app's private storage (a rooted device, an ADB backup exploit, forensic extraction)
	 * does not expose contacts' profile pictures.
	 */
	private static final KeystoreAes ICON_AT_REST= new KeystoreAes("eccles_icon_storage_key");

	public static Bitmap queryDeviceIcon(String add,Context con){
		String data= con.getSharedPreferences(DEVICE_ICON_STORAGE,Context.MODE_PRIVATE).getString(add,null);
		if(data == null) return ((BitmapDrawable)con.getResources().getDrawable(R.drawable.user)).getBitmap();

		byte[] encrypted= data.getBytes(StandardCharsets.ISO_8859_1);
		byte[] d;
		try{
			d= ICON_AT_REST.decrypt(encrypted);
		} catch (Exception e){
			android.util.Log.e("ClassicCompat","failed to decrypt stored device icon for "+add,e);
			return ((BitmapDrawable)con.getResources().getDrawable(R.drawable.user)).getBitmap();
		}
		// Delegates to EcclesIcon.convertToBitmap() for bounded decoding (dimension-checked
		// before any pixel memory is allocated) rather than calling BitmapFactory directly -
		// see that method's doc for why an unbounded decode of peer-supplied icon data is a
		// real memory-exhaustion risk.
		Bitmap b= EcclesIcon.convertToBitmap(d);
		return b != null ? b : ((BitmapDrawable)con.getResources().getDrawable(R.drawable.user)).getBitmap();
	}

	public static void saveDeviceIcon(String add,byte[] data,Context con){
		try{
			byte[] encrypted= ICON_AT_REST.encrypt(data);
			con.getSharedPreferences(DEVICE_ICON_STORAGE,Context.MODE_PRIVATE).edit()
					.putString(add,new String(encrypted,StandardCharsets.ISO_8859_1)).apply();
		} catch (Exception e){
			// Fail closed, matching ChatBase's at-rest encryption policy: never fall back to
			// storing the plaintext icon bytes unencrypted just because encryption failed.
			android.util.Log.e("ClassicCompat","failed to encrypt device icon for "+add+", not storing it",e);
		}
	}

	public static void deleteDeviceIcon(String add,Context c){
		c.getSharedPreferences(DEVICE_ICON_STORAGE,Context.MODE_PRIVATE).edit().remove(add).apply();
	}

	public static String purifyAddress(String add){
		StringBuilder builder=  new StringBuilder();
		builder.append("Eccles");

		for (String s:add.split("")){
			if(!s.equals(":")){
				builder.append(s);
			}
		}
		return builder.toString();
	}

	public static boolean isRecepient(Context c,String add){
		return c.getSharedPreferences(ICON_RECEPIENTS,Context.MODE_PRIVATE).getBoolean(add,false);
	}

	public static void clearRecipients(Context c){
		c.deleteSharedPreferences(ICON_RECEPIENTS);
	}

	public static void putRecipient(Context c,String add){
		c.getSharedPreferences(ICON_RECEPIENTS,Context.MODE_PRIVATE).edit().putBoolean(add,true).apply();
	}

	public static void view(EcclesActivity act,String add){
		Intent i= new Intent(act,ViewActivity.class);
		i.putExtra("type",ViewActivity.VIEW_PROFILE);
		i.putExtra("address",add);
		act.startActivity(i);
	}

	public static RoundedBitmapDrawable createUnreadBit(Context con,String text,int bg,int tc){
		Bitmap b= Bitmap.createBitmap(30,30,Bitmap.Config.ARGB_8888);
		Canvas can= new Canvas(b);
		Paint p= new Paint();
		p.setFakeBoldText(true);
		p.setColor(tc);
		p.setTextSize(21.5f);
		p.setTextAlign(Paint.Align.CENTER);
		p.setTypeface(Typeface.DEFAULT_BOLD);

		can.drawColor(bg);
		can.drawText(text,15,21,p);

		return EcclesIcon.resize(b.getHeight(),b.getWidth(),b,con);
	}

	public static int queryDeviceUnreads(Context con,String add){
		return con.getSharedPreferences(DEVICE_UNREAD_STORE,Context.MODE_PRIVATE).getInt(add,0);
	}

	public static void newUnread(Context con,String add){
		int i= queryDeviceUnreads(con,add);
		con.getSharedPreferences(DEVICE_UNREAD_STORE,Context.MODE_PRIVATE).edit().putInt(add,i+1).apply();
	}

	public static int totalUnRead(Context con){
		Map<String,?> unreads= con.getSharedPreferences(DEVICE_UNREAD_STORE,Context.MODE_PRIVATE).getAll();
		if(unreads.size()>0){
		int res= 0;
		for(String k:unreads.keySet()){
			res+= (int)unreads.get(k);
		}
		return res;
		}
		return 0;
	}

	public static void clearUnreads(Context c,String add){
		c.getSharedPreferences(DEVICE_UNREAD_STORE,Context.MODE_PRIVATE).edit().remove(add).apply();
	}

	public static boolean isConnected(BluetoothDevice device) {
		try {
			Method m = device.getClass().getMethod("isConnected", (Class[]) null);
			boolean connected = (boolean) m.invoke(device, (Object[]) null);
			return connected;
			} catch (Exception e) {

			return false;
		}
	}

	public static boolean isPro(Context c){
		EcclesApplication app= (EcclesApplication) c.getApplicationContext();
		return app.pref.getBoolean("pro_user",false);
	}

	public static void makePro(Context c){
		EcclesApplication app= (EcclesApplication) c.getApplicationContext();
		app.pref.edit().putBoolean("pro_user",true).apply();
	}

	public static RoundedBitmapDrawable createCallBit(Context c,int t){
		int src=0;
		switch (t){
			case 1:
				src= android.R.drawable.sym_call_outgoing;
				break;
			case 2:
				src= android.R.drawable.sym_call_incoming;
				break;
			case 3:
				src= android.R.drawable.sym_call_missed;
				break;
			default:
				throw new RuntimeException("invalid call type");
		}

		return EcclesIcon.resize(40,40,src,c);
	}

	public static void newMiss(Context c,boolean video){
		SharedPreferences mp= c.getSharedPreferences("missed_calls",Context.MODE_PRIVATE);
		int i= video ? mp.getInt("video",0) : mp.getInt("voice",0);
		mp.edit().putInt(video?"video":"voice",i+1).apply();
	}

	public static int getMiss(Context c,boolean video){
		return c.getSharedPreferences("missed_calls",Context.MODE_PRIVATE).getInt(video?"video":"voice",0);
	}

	public static void clearMiss(Context c,boolean video){
		c.getSharedPreferences("missed_calls",Context.MODE_PRIVATE).edit().remove(video?"video":"voice").apply();
	}

	public static boolean checkOnline(BluetoothDevice d){
		try{
		ParcelUuid[] uuids= d.getUuids();
		for(ParcelUuid pu:uuids){
			if(pu.getUuid().equals(uuid)) return true;
		}
		return false;
		} catch (Exception e){
			return false;
		}
	}

}