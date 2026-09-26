package starking.eccles.bluechat.pref;

import android.app.Dialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.preference.CheckBoxPreference;
import androidx.preference.DialogPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceDialogFragmentCompat;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import java.util.Set;
import starking.eccles.bluechat.EcclesActivity;
import starking.eccles.bluechat.EcclesApplication;
import starking.eccles.bluechat.R;
import starking.eccles.bluechat.activities.PreferenceActivity;

public class BlockedPreference extends PreferenceFragmentCompat {

		@Override
		public void onCreatePreferences(Bundle b,String rk){
			setPreferencesFromResource(R.xml.block_pref,rk);
			final PreferenceScreen screen= getPreferenceScreen();
			PreferenceActivity act= (PreferenceActivity) getActivity();
			Set<String> st= getActivity().getSharedPreferences("Blocked_list",Context.MODE_PRIVATE).getAll().keySet();
			if(st.size()>0){
				for(String s:st){
					BluetoothDevice d= ((EcclesApplication)getActivity().getApplication()).adapter.getRemoteDevice(s);
					if(d != null && d.getName() != null){
						CheckBoxPreference cb= new CheckBoxPreference(getActivity());
						cb.setTitle(d.getName());
						cb.setSummary(d.getAddress());
						cb.setChecked(true);
						cb.setOnPreferenceChangeListener((Preference p,Object v)->{
							if(!(boolean)v){
								((EcclesActivity)getActivity()).block(d.getAddress(),EcclesActivity.BLOCK_UNBLOCK);
								screen.removePreference(p);
							}
							return false;
						});
						screen.addPreference(cb);
					}
				}
				// Moved outside the per-device loop: previously this was set once per
				// successfully-resolved device inside the if() above (harmlessly redundant
				// when every device resolved, since the value doesn't change between
				// iterations) but was never set at all if every blocked device's name failed
				// to resolve (e.g. no longer paired/cached by the OS) - the ActionBar would
				// then just keep whatever title it already had, misleadingly implying no
				// devices were blocked.
				act.getSupportActionBar().setTitle(st.size()+" Blocked devices found");
				act.getSupportActionBar().setSubtitle("Uncheck to unBlock");
			} else {
				act.getSupportActionBar().setTitle("No Blocked devices Found");
				act.getSupportActionBar().setSubtitle("tap and hold on a device to unblock it");
			}

		}
}