package starking.eccles.bluechat.pref;

import android.app.Dialog;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceDialogFragmentCompat;
import androidx.preference.PreferenceFragmentCompat;
import starking.eccles.bluechat.R;

public class HelpPref extends PreferenceFragmentCompat {

	@Override
	public void onCreatePreferences(Bundle b,String rk){
		setPreferencesFromResource(R.xml.help_pref,rk);

		for(int i=0;i<getPreferenceScreen().getPreferenceCount();i++){
			getPreferenceScreen().getPreference(i).setOnPreferenceClickListener((Preference p)->{
				new HelpDetails(p.getTitle().toString(),p.getSummary().toString(),p.getIcon()).show(getActivity().getSupportFragmentManager(),"Help detail");
				return false;
			});
		}
	}

	public static final class HelpDetails extends DialogFragment {

		private static final String ARG_TITLE= "title";
		private static final String ARG_MSG= "msg";

		private Drawable icon;

		/**
		 * DialogFragment subclasses must have a public no-arg constructor: if the system needs
		 * to recreate this fragment (a screen rotation, or process death + restoration while
		 * this dialog was open), it does so via reflection using exactly this constructor -
		 * there previously wasn't one (only a 3-arg constructor storing title/msg/icon as
		 * plain instance fields), which would throw InstantiationException and crash the app
		 * on that recreation. title/msg are now passed via setArguments(), which survives
		 * recreation correctly; icon is not, since Drawable isn't reliably Bundle-safe - it
		 * will simply be absent (no icon shown, not a crash) in the rare case this dialog is
		 * recreated by the system rather than freshly created by HelpPref.
		 */
		public HelpDetails(){
		}

		public HelpDetails(String t,String m,Drawable ic){
			Bundle args= new Bundle();
			args.putString(ARG_TITLE,t);
			args.putString(ARG_MSG,m);
			setArguments(args);
			icon= ic;
		}

		@Override
		public Dialog onCreateDialog(Bundle b){
			Bundle args= getArguments();
			String title= args != null ? args.getString(ARG_TITLE) : null;
			String msg= args != null ? args.getString(ARG_MSG) : null;
			return new AlertDialog.Builder(getActivity())
					.setTitle(title)
					.setMessage(msg)
					.setCancelable(true)
					.setIcon(icon)
					.create();
		}
	}
}