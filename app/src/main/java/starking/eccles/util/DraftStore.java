package starking.eccles.util;

import android.content.Context;
import android.content.SharedPreferences;

/** Unsent chat text, kept per device address so it survives leaving the chat or the app. */
public class DraftStore {

	private static final String STORE= "starking.eccles.bluechat.DRAFTS";

	private static SharedPreferences prefs(Context c){
		return c.getApplicationContext().getSharedPreferences(STORE,Context.MODE_PRIVATE);
	}

	/** @return the saved draft for this address, or null if there is none. */
	public static String get(Context c,String address){
		if(c == null || address == null) return null;
		String d= prefs(c).getString(address,null);
		return d == null || d.trim().isEmpty() ? null : d;
	}

	/** Saves the text, or clears the draft when it is null/blank. */
	public static void set(Context c,String address,String text){
		if(c == null || address == null) return;
		if(text == null || text.trim().isEmpty()){
			prefs(c).edit().remove(address).apply();
		} else {
			prefs(c).edit().putString(address,text).apply();
		}
	}
}
