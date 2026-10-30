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

		// "theme" means "is a manual override currently active", not "is dark mode on" - and its
		// title describes the action a tap would take, always the opposite of whatever is
		// actually rendering right now. Two real bugs this replaces:
		// 1) The switch's checked state used to come from this same boolean read fresh with no
		//    sync to reality - so if the *system* was in dark mode and the user had simply never
		//    touched this switch, the app correctly rendered dark (AppCompatDelegate's own
		//    implicit default is MODE_NIGHT_FOLLOW_SYSTEM), but the switch still showed "off",
		//    because that boolean was never anything but a disconnected, separately-tracked flag.
		// 2) Turning the switch OFF used to force MODE_NIGHT_NO (light), not
		//    MODE_NIGHT_FOLLOW_SYSTEM - so there was no way to get back to following the system
		//    theme at all once you'd touched this switch even once; "off" always meant "forced
		//    light", never "whatever the system is doing".
		SwitchPreferenceCompat themePref= (SwitchPreferenceCompat) findPreference("theme");
		// NOT "!= MODE_NIGHT_FOLLOW_SYSTEM": before setDefaultNightMode() has ever actually been
		// called, AppCompatDelegate.getDefaultNightMode() returns MODE_NIGHT_UNSPECIFIED, not
		// MODE_NIGHT_FOLLOW_SYSTEM - they're different constants, even though UNSPECIFIED
		// behaves like following the system in practice. Comparing against FOLLOW_SYSTEM meant
		// a fresh install (nothing ever set) read as "override active" and showed the switch
		// checked by default, before the user had ever touched it - checking for the two actual
		// override values directly is the correct, version-proof way to ask "is an override
		// active" regardless of which particular "no override" sentinel is currently in use.
		int currentMode= AppCompatDelegate.getDefaultNightMode();
		boolean overrideActive= currentMode == AppCompatDelegate.MODE_NIGHT_YES || currentMode == AppCompatDelegate.MODE_NIGHT_NO;
		themePref.setChecked(overrideActive);
		updateThemeTitle(themePref);

		themePref.setOnPreferenceChangeListener((Preference p,Object nv)->{
			if((boolean)nv){
				// Turning the override on: go to the opposite of whatever is actually showing
				// right now (which, if no override was active before this tap, is just
				// whatever the system itself currently is).
				AppCompatDelegate.setDefaultNightMode(isCurrentlyDark() ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES);
			} else {
				// Turning the override off: this is the "toggle back out of a manual override
				// and let the system theme setting drive it again" case - not "force the
				// opposite of whatever it was forced to", which would just be another override.
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
			}
			return true;
		});

		// Turning the lock OFF has to be authenticated too - otherwise anyone holding the phone
		// could just open Settings and switch it off.
		final SwitchPreferenceCompat lockPref= (SwitchPreferenceCompat) findPreference("biometric_lock");
		if(lockPref != null){
			lockPref.setOnPreferenceChangeListener((Preference p,Object nv)->{
				if((boolean) nv) return true;
				final int auth= androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK | androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL;
				if(getActivity() == null || androidx.biometric.BiometricManager.from(getActivity()).canAuthenticate(auth) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS){
					return true;
				}
				androidx.biometric.BiometricPrompt.PromptInfo info= new androidx.biometric.BiometricPrompt.PromptInfo.Builder()
						.setTitle("Turn off app lock")
						.setSubtitle("Confirm your fingerprint or device PIN/pattern/password")
						.setAllowedAuthenticators(auth)
						.build();
				new androidx.biometric.BiometricPrompt(getActivity(),androidx.core.content.ContextCompat.getMainExecutor(getActivity()),
						new androidx.biometric.BiometricPrompt.AuthenticationCallback(){
							@Override
							public void onAuthenticationSucceeded(androidx.biometric.BiometricPrompt.AuthenticationResult r){
								super.onAuthenticationSucceeded(r);
								lockPref.setChecked(false);
							}
						}).authenticate(info);
				return false;
			});
		}

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

	private boolean isCurrentlyDark(){
		int uiMode= getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
		return uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
	}

	/** Title always describes the action a tap would take - "Set Night Mode" while currently
	 *  light, "Set Light Mode" while currently dark - rather than a fixed label, so it's never
	 *  ambiguous which direction tapping it goes in, regardless of whether the current mode
	 *  came from an override or from just following the system. */
	private void updateThemeTitle(Preference p){
		p.setTitle(isCurrentlyDark() ? "Switch to light mode" : "Switch to dark mode");
	}
}