package starking.eccles.bluechat.pref;

import android.os.Bundle;
import android.widget.Toast;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import starking.eccles.bluechat.EcclesApplication;
import starking.eccles.bluechat.R;
import starking.eccles.util.PeerStore;

public class AccountPref extends PreferenceFragmentCompat {

	@Override
	public void onCreatePreferences(Bundle b,String rk){
		setPreferencesFromResource(R.xml.account_pref,rk);

		final EditTextPreference name= findPreference("name");
		final EcclesApplication app= (EcclesApplication) getActivity().getApplication();

		// The name is stored inside Bluechat only. It never renames the phone's Bluetooth name.
		name.setTitle(PeerStore.ownName());
		name.setIcon(android.R.drawable.ic_menu_edit);
		name.setDialogIcon(android.R.drawable.ic_menu_edit);
		name.setDialogTitle("Your name on Bluechat");
		name.setPositiveButtonText("Save");

		name.setOnPreferenceChangeListener((Preference p,Object nv)->{
			String v= nv == null ? "" : nv.toString().trim();
			if(v.length() > PeerStore.MAX_NAME){
				Toast.makeText(getActivity(),"Please use "+PeerStore.MAX_NAME+" characters or fewer",Toast.LENGTH_SHORT).show();
				return false;
			}
			// Persist first so ownName() (which reads the saved value) returns the new name,
			// then tell everyone currently connected.
			getPreferenceManager().getSharedPreferences().edit().putString("name",v).commit();
			name.setTitle(PeerStore.ownName());
			app.broadcastProfile();
			if(v.length() == 0){
				Toast.makeText(getActivity(),"Using your phone's name again",Toast.LENGTH_SHORT).show();
			}
			return false;
		});
	}
}
