package starking.eccles.bluechat.pref;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import java.util.Set;
import starking.eccles.bluechat.EcclesApplication;
import starking.eccles.bluechat.R;
import starking.eccles.bluechat.activities.AccountActivity;
import starking.eccles.bluechat.activities.PreferenceActivity;

public class EcclesPreference extends PreferenceFragmentCompat {

	@Override
	public void onCreatePreferences(Bundle b,String rk){
		setPreferencesFromResource(R.xml.preference,rk);

		findPreference("profile").setOnPreferenceClickListener((Preference p)->{
			ActivityCompat.startActivity(getActivity(),new Intent(getActivity(),AccountActivity.class),null);
			return false;
		});

		findPreference("feedback").setOnPreferenceClickListener((Preference p)->{
			Intent i= new Intent();
			i.setAction(Intent.ACTION_VIEW);

			Uri u= Uri.parse("mailto:?subject=" + "BlueChat Feedback"+ "&body=" + "Eccles Apologize for any inconvenience you encountered while using this app\nEccles will do their best to make sure your problems are solved\n" + "&to=" + "nwobodoeccles@gmail.com" +"&from=" + "");
			i.setData(u);
			ActivityCompat.startActivity(getActivity(),Intent.createChooser(i,"BlueChat Feedback"),null);
			return false;
		});

		findPreference("theme").setOnPreferenceChangeListener((Preference p,Object nv)->{
			int mode= 0;
			if((boolean)nv){
				mode= AppCompatDelegate.MODE_NIGHT_YES;
			} else {
				mode= AppCompatDelegate.MODE_NIGHT_NO;
			}

			AppCompatDelegate.setDefaultNightMode(mode);
			return true;
		});

		final Preference autoDisable= findPreference("auto_disable");
		final Preference autoIf= findPreference("auto_if");
		if(autoDisable.getSharedPreferences().getBoolean("auto_disable",false)){
			autoIf.setEnabled(false);
		} else if(autoIf.getSharedPreferences().getBoolean("auto_if",false)){
			autoDisable.setEnabled(false);
		}
		autoDisable.setOnPreferenceChangeListener((Preference p,Object nv)->{
			if((boolean)nv){
				autoIf.setEnabled(false);
			} else {
				autoIf.setEnabled(true);
			}
			return true;
		});
		autoIf.setOnPreferenceChangeListener((Preference p,Object nv)->{
			if((boolean)nv){
				autoDisable.setEnabled(false);
			} else {
				autoDisable.setEnabled(true);
			}
			return true;
		});

		findPreference("tone").setOnPreferenceClickListener((Preference p)->{
			Intent ti= new Intent();
			ti.setType("audio/*");
			ti.setAction(Intent.ACTION_GET_CONTENT);
			getActivity().startActivityForResult(ti,PreferenceActivity.RING_CODE);
			return false;
		});

		findPreference("blocked").setOnPreferenceClickListener((Preference p)->{
			((PreferenceActivity)getActivity()).update(new BlockedPreference());
			return false;
		});
	}
}