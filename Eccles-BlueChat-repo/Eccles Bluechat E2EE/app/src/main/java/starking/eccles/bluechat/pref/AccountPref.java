package starking.eccles.bluechat.pref;

import android.os.Bundle;
import android.widget.Toast;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import starking.eccles.bluechat.EcclesApplication;
import starking.eccles.bluechat.R;

public class AccountPref extends PreferenceFragmentCompat {

	@Override
	public void onCreatePreferences(Bundle b,String rk){
		setPreferencesFromResource(R.xml.account_pref,rk);

		EditTextPreference name= findPreference("name");
		Preference address= findPreference("address");

		final EcclesApplication app= (EcclesApplication) getActivity().getApplication();

		name.setTitle(app.name);
		name.setIcon(android.R.drawable.ic_menu_edit);
		name.setDialogIcon(android.R.drawable.ic_menu_edit);
		name.setPositiveButtonText("Change");

		name.setOnPreferenceChangeListener((Preference p,Object nv)->{

			if(!app.adapter.setName(nv.toString())){
				Toast.makeText(getActivity(),"failed to update name",1).show();
				return false;
			}
			name.setTitle(app.adapter.getName());
			return true;
		});
		address.setTitle(app.address);

	}
}